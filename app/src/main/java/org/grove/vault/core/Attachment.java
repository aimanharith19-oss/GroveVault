package org.grove.vault.core;

public class Attachment {
    public String name;
    public String mime;
    public byte[] data;

    public Attachment(String name, String mime, byte[] data) {
        this.name = name;
        this.mime = mime == null ? "application/octet-stream" : mime;
        this.data = data;
    }

    public boolean isImage() {
        return mime != null && mime.startsWith("image/");
    }
}
