package link.e4all.dialtone;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.util.internal.StringUtil;
import link.e4all.E4allClient;
import link.e4all.voice.VoiceEndpointStack;
import link.e4mc.iroh.Connection;
import link.e4mc.iroh.Endpoint;
import link.e4mc.iroh.Stream;

import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class DialtoneChannel extends AbstractChannel {
    private static final ChannelMetadata METADATA = new ChannelMetadata(false);
    private static final int COALESCE_THRESHOLD = 2048;
    private static final int MAX_COALESCE_BYTES = 32 * 1024;
    // bigger reads = fewer round trips, guests used to fall through unloaded chunks
    private static final int READ_CHUNK_BYTES = 256 * 1024;
    private final ChannelConfig config = new DefaultChannelConfig(this);
    Endpoint endpoint;
    Connection connection;
    Stream stream;
    volatile boolean closed = false;
    AtomicBoolean readInFlight = new AtomicBoolean(false);
    AtomicBoolean writeInFlight = new AtomicBoolean(false);
    AtomicBoolean writePending = new AtomicBoolean(false);
    private long bytesRead;
    private ChannelPromise connectPromise;


    public DialtoneChannel() {
        super(null);
    }

    DialtoneChannel(DialtoneServerChannel parent) {
        super(parent);
        endpoint = parent.endpoint;
    }

    public byte[] exportKeyingMaterial(byte[] label, byte[] context, int length) {
        return connection.exportKeyingMaterial(label, context, length);
    }

    public String debugInfo() {
        if (connection == null) {
            return "dialtone (connection not established yet)";
        }
        return connection.debugInfo();
    }

    @Override
    protected AbstractUnsafe newUnsafe() {
        return new DialtoneUnsafe();
    }

    @Override
    protected boolean isCompatible(EventLoop loop) {
        return true;
    }

    @Override
    protected SocketAddress localAddress0() {
        return endpoint != null ? new DialtoneAddress(endpoint.address()) : null;
    }

    @Override
    protected SocketAddress remoteAddress0() {
        return connection != null ? new DialtoneAddress(connection.peerAddress()) : null;
    }

    @Override
    protected void doBind(SocketAddress localAddress) {
        throw new UnsupportedOperationException();
    }

    @Override
    protected void doDisconnect() {
        doClose();
    }

    @Override
    protected void doClose() {
        if (closed) {
            return;
        }
        closed = true;
        ChannelPromise cp = this.connectPromise;
        if (cp != null && !cp.isDone()) {
            this.connectPromise = null;
            cp.tryFailure(new java.nio.channels.ClosedChannelException());
        }
        try {
            if (stream != null) {
                stream.close();
            }
        } catch (Throwable e) {
            E4allClient.LOGGER.warn("Error closing dialtone stream", e);
        }
        final Connection connToClose = this.connection;
        if (connToClose != null) {
            try {
                eventLoop().schedule(() -> {
                    try {
                        connToClose.close(0, new byte[0]);
                    } catch (Throwable e) {
                        E4allClient.LOGGER.debug("Error closing delayed dialtone connection", e);
                    }
                }, 3, TimeUnit.SECONDS);
            } catch (Throwable t) {
                try {
                    connToClose.close(0, new byte[0]);
                } catch (Throwable ignored) {
                }
            }
        }
        stream = null;
        connection = null;
    }

    @Override
    protected void doBeginRead() {
        if (!isActive()) {
            return;
        }
        if (!readInFlight.compareAndSet(false, true)) {
            return;
        }
        stream.readIrohStreamByteArray(READ_CHUNK_BYTES).thenAccept(arr -> {
            readInFlight.set(false);
            // All pipeline events must be fired on the event loop thread.
            // The iroh CompletableFuture may complete on a native thread.
            eventLoop().execute(() -> {
                if (arr == null || arr.length == 0) {
                    if (!closed) {
                        // EOF: use close() so channelInactive fires and handlers can deregister
                        close();
                    }
                    return;
                }
                trackRead(arr.length);
                pipeline().fireChannelRead(Unpooled.wrappedBuffer(arr));
                pipeline().fireChannelReadComplete();
                // Schedule next read on event loop to avoid stack overflow from
                // synchronous CompletableFuture completion
                if (isActive() && config().isAutoRead()) {
                    doBeginRead();
                }
            });
        }).exceptionally(t -> {
            readInFlight.set(false);
            eventLoop().execute(() -> {
                if (!closed) {
                    if (isConnectionLost(t)) {
                        close();
                    } else {
                        pipeline().fireExceptionCaught(t);
                    }
                }
            });
            return null;
        });
    }

    public static boolean isConnectionLost(Throwable t) {
        if (t == null) return false;
        Throwable curr = t;
        while (curr != null) {
            if (curr instanceof link.e4mc.iroh.NativeException || curr instanceof java.io.IOException) {
                String msg = curr.getMessage();
                if (msg != null) {
                    String lower = msg.toLowerCase();
                    if (lower.contains("connection lost")
                            || lower.contains("connection closed")
                            || lower.contains("reset")
                            || lower.contains("broken pipe")
                            || lower.contains("timed out")
                            || lower.contains("shutting down")
                            || lower.contains("not connected")) {
                        return true;
                    }
                }
            }
            curr = curr.getCause();
        }
        return false;
    }

    private void trackRead(int bytes) {
        bytesRead += bytes;
    }

    @Override
    protected void doWrite(ChannelOutboundBuffer in) {
        if (!writeInFlight.compareAndSet(false, true)) {
            writePending.set(true);
            return;
        }
        doWriteNext(in);
    }

    private void doWriteNext(ChannelOutboundBuffer in) {
        ByteBuf buf = null;
        while (true) {
            Object msg = in.current();
            if (msg == null) {
                writeInFlight.set(false);
                if (writePending.compareAndSet(true, false) && !closed) {
                    eventLoop().execute(() -> unsafe().flush());
                }
                return;
            }
            if (!(msg instanceof ByteBuf candidate)) {
                in.remove(new UnsupportedOperationException(
                        "unsupported message type: " + StringUtil.simpleClassName(msg)));
                writeInFlight.set(false);
                return;
            }
            if (!candidate.isReadable()) {
                in.remove();
                continue;
            }
            buf = candidate;
            break;
        }

        if (in.nioBufferCount() > 1 && buf.readableBytes() <= COALESCE_THRESHOLD && coalesceAndWrite(in)) {
            return;
        }

        // single-message path (original behavior)
        ByteBuffer byteBuffer = null;
        try {
            byteBuffer = buf.nioBuffer();
            if (!byteBuffer.isDirect()) {
                byteBuffer = null;
            }
        } catch (UnsupportedOperationException ignored) {
        }
        try {
            if (byteBuffer == null) {
                int len = buf.readableBytes();
                byte[] arr = new byte[len];
                buf.readBytes(arr, 0, len);
                stream.writeIrohStreamByteArray(arr, 0, arr.length).thenAccept(nothing -> {
                    eventLoop().execute(() -> {
                        in.remove();
                        doWriteNext(in);
                    });
                }).exceptionally(t -> {
                    eventLoop().execute(() -> {
                        in.remove(t);
                        writeInFlight.set(false);
                    });
                    return null;
                });
            } else {
                final int pos = byteBuffer.position();
                final int rem = byteBuffer.remaining();
                stream.writeIrohStreamByteBuffer(byteBuffer, pos, rem).thenAccept(nothing -> {
                    eventLoop().execute(() -> {
                        in.remove();
                        doWriteNext(in);
                    });
                }).exceptionally(t -> {
                    eventLoop().execute(() -> {
                        in.remove(t);
                        writeInFlight.set(false);
                    });
                    return null;
                });
            }
        } catch (Throwable e) {
            in.remove(e);
            writeInFlight.set(false);
        }
    }

    private boolean coalesceAndWrite(ChannelOutboundBuffer in) {
        int count = in.nioBufferCount();
        if (count <= 1) {
            return false;
        }
        ByteBuffer[] nioBuffers = in.nioBuffers(1024, MAX_COALESCE_BYTES);
        count = in.nioBufferCount();
        if (count <= 1) {
            return false;
        }
        int bytesToWrite = (int) Math.min(in.nioBufferSize(), MAX_COALESCE_BYTES);
        if (bytesToWrite <= 0) {
            return false;
        }

        byte[] arr = new byte[bytesToWrite];
        int written = 0;
        for (int i = 0; i < count && written < bytesToWrite; i++) {
            ByteBuffer nioBuf = nioBuffers[i];
            int rem = Math.min(nioBuf.remaining(), bytesToWrite - written);
            nioBuf.get(arr, written, rem);
            written += rem;
        }

        final int finalWritten = written;
        try {
            stream.writeIrohStreamByteArray(arr, 0, finalWritten).thenAccept(nothing -> {
                eventLoop().execute(() -> {
                    in.removeBytes(finalWritten);
                    doWriteNext(in);
                });
            }).exceptionally(t -> {
                eventLoop().execute(() -> writeFailed(t));
                return null;
            });
        } catch (Throwable e) {
            writeFailed(e);
        }
        return true;
    }

    private void writeFailed(Throwable t) {
        writeInFlight.set(false);
        if (!closed) {
            if (isConnectionLost(t)) {
                close();
            } else {
                pipeline().fireExceptionCaught(t);
            }
        }
    }

    private void discardLateConnection(Connection conn) {
        try {
            conn.close(0, new byte[0]);
        } catch (Throwable t) {
            E4allClient.LOGGER.debug("e4all: error closing late dialtone connection", t);
        }
        if (connection == conn) {
            connection = null;
        }
        stream = null;
        closed = true;
    }

    @Override
    public ChannelConfig config() {
        return config;
    }

    @Override
    public boolean isOpen() {
        return !closed;
    }

    @Override
    public boolean isActive() {
        return stream != null && !closed;
    }

    @Override
    public ChannelMetadata metadata() {
        return METADATA;
    }

    private class DialtoneUnsafe extends AbstractUnsafe {
        @Override
        public void connect(SocketAddress remoteAddress, SocketAddress localAddress, ChannelPromise promise) {
            if (!promise.setUncancellable() || !ensureOpen(promise)) {
                return;
            }
            connectPromise = promise;

            try {
                if (remoteAddress instanceof DialtoneAddress dialtoneAddress) {
                    // voice uses the dedicated relay-less endpoint, so voice stays direct
                    Endpoint connectEndpoint;
                    if (DialtoneAddress.VOICE_ALPN.equals(dialtoneAddress.alpn)) {
                        connectEndpoint = VoiceEndpointStack.INSTANCE.getOrCreate();
                    } else {
                        connectEndpoint = DialtoneAmbientSession.INSTANCE.getOrStart();
                    }
                    endpoint = connectEndpoint;
                    connectEndpoint
                            .connect(dialtoneAddress.actualAddress, dialtoneAddress.alpn.getBytes(StandardCharsets.UTF_8))
                            .thenAccept(conn -> {
                                if (closed) {
                                    discardLateConnection(conn);
                                    connectPromise = null;
                                    safeSetFailure(promise, new java.nio.channels.ClosedChannelException());
                                    return;
                                }
                                connection = conn;
                                conn.openBi().thenAccept(bidi -> {
                                    if (closed) {
                                        try { bidi.close(); } catch (Throwable ignored) {}
                                        discardLateConnection(conn);
                                        connectPromise = null;
                                        safeSetFailure(promise, new java.nio.channels.ClosedChannelException());
                                        return;
                                    }
                                    stream = bidi;
                                    eventLoop().execute(() -> {
                                        if (closed) {
                                            try { bidi.close(); } catch (Throwable ignored) {}
                                            discardLateConnection(conn);
                                            connectPromise = null;
                                            safeSetFailure(promise, new java.nio.channels.ClosedChannelException());
                                            return;
                                        }
                                        connectPromise = null;
                                        pipeline().fireChannelActive();
                                        safeSetSuccess(promise);
                                    });
                                }).exceptionally(t -> {
                                    connectPromise = null;
                                    eventLoop().execute(() -> safeSetFailure(promise, annotateConnectException(t, remoteAddress)));
                                    return null;
                                });
                            }).exceptionally(t -> {
                                connectPromise = null;
                                eventLoop().execute(() -> safeSetFailure(promise, annotateConnectException(t, remoteAddress)));
                                return null;
                            });
                } else {
                    connectPromise = null;
                    throw new UnsupportedOperationException();
                }
            } catch (Throwable t) {
                connectPromise = null;
                safeSetFailure(promise, annotateConnectException(t, remoteAddress));
            }
        }
    }
}
