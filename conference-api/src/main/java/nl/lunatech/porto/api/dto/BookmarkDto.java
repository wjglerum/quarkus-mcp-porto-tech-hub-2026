package nl.lunatech.porto.api.dto;

import nl.lunatech.porto.api.domain.Bookmark;

import java.time.OffsetDateTime;

public record BookmarkDto(Long id, Long sessionId, OffsetDateTime createdAt, SessionDto session) {
    public static BookmarkDto of(Bookmark b) {
        return new BookmarkDto(b.id, b.session.id, b.createdAt, SessionDto.of(b.session));
    }
}
