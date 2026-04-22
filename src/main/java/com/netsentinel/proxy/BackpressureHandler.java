package com.netsentinel.proxy;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;

public final class BackpressureHandler extends ChannelInboundHandlerAdapter {
    @Override
    public void channelWritabilityChanged(ChannelHandlerContext context) throws Exception {
        context.channel().config().setAutoRead(context.channel().isWritable());
        super.channelWritabilityChanged(context);
    }
}
