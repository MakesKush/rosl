package org.example.model;

public enum Feature {
    // likes
    LIKES_SPORTS("likes_sports"),
    LIKES_GAMES("likes_games"),
    LIKES_MUSIC("likes_music"),
    LIKES_MOVIES("likes_movies"),
    LIKES_MEMES("likes_memes"),

    // comments
    COMMENTS_SPORTS("comments_sports"),
    COMMENTS_GAMES("comments_games"),
    COMMENTS_MUSIC("comments_music"),
    COMMENTS_MOVIES("comments_movies"),
    COMMENTS_MEMES("comments_memes"),

    // posts
    POSTS_SPORTS("posts_sports"),
    POSTS_GAMES("posts_games"),
    POSTS_MUSIC("posts_music"),
    POSTS_MOVIES("posts_movies"),
    POSTS_MEMES("posts_memes");

    public final String column;

    Feature(String column) {
        this.column = column;
    }

    public static int count() {
        return values().length; // 15
    }
}
