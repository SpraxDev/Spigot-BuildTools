package org.spigotmc.builder.hasher;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public class HasherMD5 extends Hasher {
    private final MessageDigest md5;

    public HasherMD5() throws NoSuchAlgorithmException {
        this.md5 = MessageDigest.getInstance("MD5");
    }

    public Hasher update(byte[] bytes) {
        md5.update(bytes);

        return this;
    }

    public String getHash() {
        return toHex(md5.digest());
    }

    @Override
    public String getHash(byte[] bytes) {
        return toHex(md5.digest(bytes));
    }
}
