package org.example.model;

public enum Feature {
    SPORTS("sports"),
    GAMES("games"),
    MUSIC("music"),
    MOVIES("movies"),
    MEMES("memes"),
    LIKES("likes"),
    COMMENTS("comments");

    public final String column;

    Feature(String column) {
        this.column = column;
    }

    public static int count() {
        return values().length;
    }
}
