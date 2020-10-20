package org.spigotmc.builder.hasher;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public class HasherSHA256 extends Hasher {
    private final MessageDigest sha256;

    public HasherSHA256() throws NoSuchAlgorithmException {
        this.sha256 = MessageDigest.getInstance("SHA-256");
    }

    public Hasher update(byte[] bytes) {
        sha256.update(bytes);

        return this;
    }

    public String getHash() {
        return toHex(sha256.digest());
    }

    @Override
    public String getHash(byte[] bytes) {
        return toHex(sha256.digest(bytes));
    }
}
