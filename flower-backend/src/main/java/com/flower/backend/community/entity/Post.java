package com.flower.backend.community.entity;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class Post {
    private final Long id;
    private final String nickname;
    private final String content;
    private final String species;
    private final int likesCount;
}
