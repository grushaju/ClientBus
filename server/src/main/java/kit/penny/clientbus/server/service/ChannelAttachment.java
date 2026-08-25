package kit.penny.clientbus.server.service;

import java.io.InputStream;

public record ChannelAttachment(

        String fileName,

        String contentType,

        long size,

        InputStream inputStream

) {
}