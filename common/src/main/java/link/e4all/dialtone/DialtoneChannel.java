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
import java.util.concurrent.atomic.AtomicBoolean;

public class DialtoneChannel extends AbstractChannel {
    private static final ChannelMetadata METADATA = new ChannelMetadata(false);
    private static final int COALESCE_THRESHOLD = 2048;
    private static final int MAX_COALESCE_BYTES = 32 * 1024;
    private final ChannelConfig config = new DefaultChannelConfig(this);
    Endpoint endpoint;
    Connection connection;
    Stream stream;
    volatile boolean closed = false;
    AtomicBoolean readInFlight = new AtomicBoolean(false);
    AtomicBoolean writeInFlight = new AtomicBoolean(false);
    AtomicBoolean writePending = new AtomicBoolean(false);


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
        try {
            if (stream != null) {
                stream.close();
            }
        } catch (Throwable e) {
            E4allClient.LOGGER.warn("Error closing dialtone stream", e);
        }
        try {
            if (connection != null) {
                connection.close(0, new byte[0]);
            }
        } catch (Throwable e) {
            E4allClient.LOGGER.warn("Error closing dialtone connection", e);
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
        stream.readIrohStreamByteArray(65536).thenAccept(arr -> {
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
                    pipeline().fireExceptionCaught(t);
                }
            });
            return null;
        });
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

        // small packets: one native write + thread hop each serializes the stream, so coalesce
        if (buf.readableBytes() <= COALESCE_THRESHOLD && coalesceAndWrite(in)) {
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
        ByteBuf agg = Unpooled.buffer(1024, MAX_COALESCE_BYTES);
        int batched = 0;
        try {
            while (true) {
                Object msg = in.current();
                if (!(msg instanceof ByteBuf candidate) || !candidate.isReadable()) {
                    break; // non-ByteBuf or empty: handled next round
                }
                if (batched > 0 && candidate.readableBytes() > agg.maxWritableBytes()) {
                    break; // won't fit: leave for the next batch
                }
                agg.writeBytes(candidate);
                in.remove();
                batched++;
            }
        } catch (Throwable t) {
            agg.release();
            writeFailed(t);
            return true;
        }
        if (batched == 0) {
            agg.release();
            return false;
        }
        byte[] arr = new byte[agg.readableBytes()];
        agg.getBytes(agg.readerIndex(), arr);
        agg.release();
        try {
            stream.writeIrohStreamByteArray(arr, 0, arr.length).thenAccept(nothing -> {
                eventLoop().execute(() -> doWriteNext(in));
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
            pipeline().fireExceptionCaught(t);
        }
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

            try {
                if (remoteAddress instanceof DialtoneAddress dialtoneAddress) {
                    // voice uses the dedicated relay-less endpoint, so voice stays direct
                    if (DialtoneAddress.VOICE_ALPN.equals(dialtoneAddress.alpn)) {
                        endpoint = VoiceEndpointStack.INSTANCE.getOrCreate();
                    } else {
                        if (DialtoneAmbientSession.INSTANCE.endpoint == null) {
                            DialtoneAmbientSession.INSTANCE.start();
                        }
                        endpoint = DialtoneAmbientSession.INSTANCE.endpoint;
                    }
                    endpoint
                            .connect(dialtoneAddress.actualAddress, dialtoneAddress.alpn.getBytes(StandardCharsets.UTF_8))
                            .thenAccept(conn -> {
                                connection = conn;
                                conn.openBi().thenAccept(bidi -> {
                                    stream = bidi;
                                    eventLoop().execute(() -> {
                                        pipeline().fireChannelActive();
                                        safeSetSuccess(promise);
                                    });
                                }).exceptionally(t -> {
                                    eventLoop().execute(() -> safeSetFailure(promise, annotateConnectException(t, remoteAddress)));
                                    return null;
                                });
                            }).exceptionally(t -> {
                                eventLoop().execute(() -> safeSetFailure(promise, annotateConnectException(t, remoteAddress)));
                                return null;
                            });
                } else {
                    throw new UnsupportedOperationException();
                }
            } catch (Throwable t) {
                safeSetFailure(promise, annotateConnectException(t, remoteAddress));
            }
        }
    }
}
