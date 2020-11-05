package org.spigotmc.builder;

import org.jetbrains.annotations.NotNull;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public enum HashAlgo {
    MD5("MD5"), SHA256("SHA-256"), SHA512("SHA-512");

    private final String algorithm;
    private MessageDigest digest;

    HashAlgo(String algorithm) {
        this.algorithm = algorithm;
    }

    public String getAlgorithm() {
        return algorithm;
    }

    public void update(@NotNull byte[] bytes) {
        getDigest().update(bytes);
    }

    @NotNull
    public String getHash() {
        return Utils.toHex(getDigest().digest());
    }

    @NotNull
    public String getHash(@NotNull byte[] bytes) {
        return Utils.toHex(getDigest().digest(bytes));
    }

    private MessageDigest getDigest() {
        if (digest == null) {
            try {
                this.digest = MessageDigest.getInstance(algorithm);
            } catch (NoSuchAlgorithmException ex) {
                ex.printStackTrace();
                System.exit(1);
            }
        }

        return digest;
    }
}