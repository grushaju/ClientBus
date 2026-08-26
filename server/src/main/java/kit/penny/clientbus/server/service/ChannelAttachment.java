package kit.penny.clientbus.server.service;

import kit.penny.clientbus.common.enums.MessageAttachmentType;

import java.io.InputStream;

public record ChannelAttachment(

        MessageAttachmentType type,

        String fileName,

        String contentType,

        long size,

        InputStream content

) {
}