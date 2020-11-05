package org.spigotmc.builder;

public class BuilderException extends Exception {
    public BuilderException(String message) {
        super(message);
    }

    public BuilderException(String message, Throwable cause) {
        super(message, cause);
    }
}
