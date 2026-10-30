package nl.lunatech.porto.api.dto;

import nl.lunatech.porto.api.domain.Speaker;

public record SpeakerRef(Long id, String name) {
    public static SpeakerRef of(Speaker s) {
        return s == null ? null : new SpeakerRef(s.id, s.name);
    }
}
