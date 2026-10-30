package nl.lunatech.porto.api.web;

import jakarta.transaction.Transactional;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import nl.lunatech.porto.api.domain.Session;
import nl.lunatech.porto.api.domain.Speaker;
import nl.lunatech.porto.api.dto.SessionDto;
import nl.lunatech.porto.api.dto.SpeakerListDto;

import java.util.List;

@Path("/api/v1/speakers")
@Produces(MediaType.APPLICATION_JSON)
public class SpeakerResource {

    @GET
    @Transactional
    public List<SpeakerListDto> list() {
        return Speaker.<Speaker>list("order by lower(name)").stream()
                .map(sp -> SpeakerListDto.of(sp,
                        Session.listForSpeaker(sp.id).stream().map(SessionDto::of).toList()))
                .toList();
    }
}
