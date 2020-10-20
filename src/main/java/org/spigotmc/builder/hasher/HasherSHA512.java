package org.spigotmc.builder.hasher;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public class HasherSHA512 extends Hasher {
    private final MessageDigest sha512;

    public HasherSHA512() throws NoSuchAlgorithmException {
        this.sha512 = MessageDigest.getInstance("SHA-512");
    }

    public Hasher update(byte[] bytes) {
        sha512.update(bytes);

        return this;
    }

    public String getHash() {
        return toHex(sha512.digest());
    }

    @Override
    public String getHash(byte[] bytes) {
        return toHex(sha512.digest(bytes));
    }
}
