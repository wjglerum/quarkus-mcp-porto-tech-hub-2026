package nl.lunatech.porto.api.dto;

import nl.lunatech.porto.api.domain.Rating;

import java.time.OffsetDateTime;

public record RatingDto(
        Long id,
        Long sessionId,
        String sessionTitle,
        String attendeeDisplayName,
        int stars,
        String comment,
        OffsetDateTime createdAt
) {
    public static RatingDto of(Rating r) {
        return new RatingDto(
                r.id,
                r.session.id,
                r.session.title,
                r.attendee.displayName,
                r.stars,
                r.comment,
                r.createdAt);
    }
}
