/*
 * Copyright (c) 2015 The Jupiter Project
 *
 * Licensed under the Apache License, version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jupiter.transport.netty.handler;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ReplayingDecoder;
import org.jupiter.common.util.Signal;
import org.jupiter.common.util.SystemClock;
import org.jupiter.common.util.SystemPropertyUtil;
import org.jupiter.rpc.JRequest;
import org.jupiter.rpc.JResponse;
import org.jupiter.transport.JProtocolHeader;

import java.util.List;

import static org.jupiter.transport.JProtocolHeader.*;
import static org.jupiter.transport.exception.IoSignals.*;

/**
 * **************************************************************************************************
 *                                          Protocol
 *  ┌ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ┐
 *       2   │   1   │    1   │     8     │      4      │
 *  ├ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ┤
 *           │       │        │           │             │
 *  │  MAGIC   Sign    Status   Invoke Id   Body Length                   Body Content              │
 *           │       │        │           │             │
 *  └ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ┘
 *
 * 消息头16个字节定长
 * = 2 // MAGIC = (short) 0xbabe
 * + 1 // 消息标志位, 低地址4位用来表示消息类型Request/Response/Heartbeat等, 高地址4位用来表示序列化类型
 * + 1 // 状态位, 设置请求响应状态
 * + 8 // 消息 id, long 类型
 * + 4 // 消息体 body 长度, int类型
 *
 * jupiter
 * org.jupiter.transport.netty.handler
 *
 * @author jiachun.fjc
 */
public class ProtocolDecoder extends ReplayingDecoder<ProtocolDecoder.State> {

    // 协议体最大限制, 默认5M
    private static final int MAX_BODY_SIZE = SystemPropertyUtil.getInt("jupiter.decoder.max.body.size", 1024 * 1024 * 5);

    /**
     * Cumulate {@link ByteBuf}s by add them to a CompositeByteBuf and so do no memory copy whenever possible.
     * Be aware that CompositeByteBuf use a more complex indexing implementation so depending on your use-case
     * and the decoder implementation this may be slower then just use the {@link #MERGE_CUMULATOR}.
     */
    private static final boolean USE_COMPOSITE_BUF = SystemPropertyUtil.getBoolean("jupiter.decoder.composite.buf", false);

    public ProtocolDecoder() {
        super(State.HEADER_MAGIC);
        if (USE_COMPOSITE_BUF) {
            setCumulator(COMPOSITE_CUMULATOR);
        }
    }

    // 协议头
    private final JProtocolHeader header = new JProtocolHeader();

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
        switch (state()) {
            case HEADER_MAGIC:
                checkMagic(in.readShort());         // MAGIC
                checkpoint(State.HEADER_SIGN);
            case HEADER_SIGN:
                header.sign(in.readByte());         // 消息标志位
                checkpoint(State.HEADER_STATUS);
            case HEADER_STATUS:
                header.status(in.readByte());       // 状态位
                checkpoint(State.HEADER_ID);
            case HEADER_ID:
                header.id(in.readLong());           // 消息id
                checkpoint(State.HEADER_BODY_LENGTH);
            case HEADER_BODY_LENGTH:
                header.bodyLength(in.readInt());    // 消息体长度
                checkpoint(State.BODY);
            case BODY:
                switch (header.messageCode()) {
                    case HEARTBEAT:
                        break;
                    case REQUEST: {
                        int bodyLength = checkBodyLength(header.bodyLength());
                        byte[] bytes = new byte[bodyLength];
                        in.readBytes(bytes);

                        JRequest request = JRequest.newInstance(header.id(), header.serializerCode());
                        request.timestamp(SystemClock.millisClock().now());
                        request.bytes(bytes);
                        out.add(request);

                        break;
                    }
                    case RESPONSE: {
                        int bodyLength = checkBodyLength(header.bodyLength());
                        byte[] bytes = new byte[bodyLength];
                        in.readBytes(bytes);

                        JResponse response = JResponse.newInstance(
                                header.id(), header.serializerCode(), header.status(), bytes);
                        out.add(response);

                        break;
                    }
                    default:
                        throw ILLEGAL_SIGN;
                }
                checkpoint(State.HEADER_MAGIC);
        }
    }

    private static void checkMagic(short magic) throws Signal {
        if (MAGIC != magic) {
            throw ILLEGAL_MAGIC;
        }
    }

    private static int checkBodyLength(int size) throws Signal {
        if (size > MAX_BODY_SIZE) {
            throw BODY_TOO_LARGE;
        }
        return size;
    }

    enum State {
        HEADER_MAGIC,
        HEADER_SIGN,
        HEADER_STATUS,
        HEADER_ID,
        HEADER_BODY_LENGTH,
        BODY
    }
}
