package link.e4all.dialtone;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.util.internal.StringUtil;
import link.e4all.E4allClient;
import link.e4mc.iroh.Connection;
import link.e4mc.iroh.Endpoint;
import link.e4mc.iroh.Stream;

import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

public class DialtoneChannel extends AbstractChannel {
    private static final ChannelMetadata METADATA = new ChannelMetadata(false);
    private final ChannelConfig config = new DefaultChannelConfig(this);
    Endpoint endpoint;
    Connection connection;
    Stream stream;
    volatile boolean closed = false;
    AtomicBoolean readInFlight = new AtomicBoolean(false);
    AtomicBoolean writeInFlight = new AtomicBoolean(false);

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
        return new DialtoneAddress(endpoint.address());
    }

    @Override
    protected SocketAddress remoteAddress0() {
        return new DialtoneAddress(connection.peerAddress());
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
        pipeline().fireChannelInactive();
    }

    @Override
    protected void doBeginRead() {
        if (!isActive()) {
            return;
        }
        if (!readInFlight.compareAndSet(false, true)) {
//            E4allClient.LOGGER.info("doBeginRead called but a read was already in flight");
            return;
        }
//        E4allClient.LOGGER.info("doBeginRead called");
        stream.readIrohStreamByteArray(65536).thenAccept(arr -> {
            readInFlight.set(false);
            if (arr == null) {
                if (!closed) {
                    doClose();
                }
                return;
            }
//            E4allClient.LOGGER.info("received {}", HexFormat.of().formatHex(arr));
            pipeline().fireChannelRead(Unpooled.wrappedBuffer(arr));
            pipeline().fireChannelReadComplete();
        }).exceptionally(t -> {
            readInFlight.set(false);
//            E4allClient.LOGGER.info("error reading", t);
            if (!closed) {
                pipeline().fireExceptionCaught(t);
            }
            return null;
        });
    }

    @Override
    protected void doWrite(ChannelOutboundBuffer in) {
        if (!writeInFlight.compareAndSet(false, true)) {
            return;
        }
        doWriteNext(in);
    }

    private void doWriteNext(ChannelOutboundBuffer in) {
        Object msg = in.current();
        if (msg == null) {
            writeInFlight.set(false);
            return;
        }
        if (msg instanceof ByteBuf buf) {
            if (!buf.isReadable()) {
                in.remove();
                doWriteNext(in);
                return;
            }
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
                    byte[] arr = new byte[buf.readableBytes()];
                    buf.readBytes(arr, 0, buf.readableBytes());
                    stream.writeIrohStreamByteArray(arr, 0, arr.length).thenAccept(nothing -> {
                        in.remove();
                        doWriteNext(in);
                    }).exceptionally(t -> {
                        in.remove(t);
                        writeInFlight.set(false);
                        return null;
                    });
                } else {
                    final int pos = byteBuffer.position();
                    final int rem = byteBuffer.remaining();
                    stream.writeIrohStreamByteBuffer(byteBuffer, pos, rem).thenAccept(nothing -> {
                        in.remove();
                        doWriteNext(in);
                    }).exceptionally(t -> {
                        in.remove(t);
                        writeInFlight.set(false);
                        return null;
                    });
                }
            } catch (Throwable e) {
                in.remove(e);
                writeInFlight.set(false);
            }
        } else {
            in.remove(new UnsupportedOperationException(
                    "unsupported message type: " + StringUtil.simpleClassName(msg)));
            writeInFlight.set(false);
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
                    if (DialtoneAmbientSession.INSTANCE.endpoint == null) {
                        DialtoneAmbientSession.INSTANCE.start();
                    }
                    endpoint = DialtoneAmbientSession.INSTANCE.endpoint;
                    DialtoneAmbientSession.INSTANCE
                            .endpoint
                            .connect(dialtoneAddress.actualAddress, "e4mc-dialtone".getBytes(StandardCharsets.UTF_8))
                            .thenAccept(conn -> {
                                connection = conn;
                                conn.openBi().thenAccept(bidi -> {
                                    stream = bidi;
                                    pipeline().fireChannelActive();
                                    safeSetSuccess(promise);
                                }).exceptionally(t -> {
                                    safeSetFailure(promise, annotateConnectException(t, remoteAddress));
                                    return null;
                                });
                            }).exceptionally(t -> {
                                safeSetFailure(promise, annotateConnectException(t, remoteAddress));
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



