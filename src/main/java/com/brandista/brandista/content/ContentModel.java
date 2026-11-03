package com.brandista.brandista.content;

import java.time.Instant;
import java.util.List;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

public final class ContentModel {
    private ContentModel() {}
    public enum Target { BOTH, MOBILE, WEBSITE }
    public enum Placement { HERO, PROMOTION, COLLECTION }
    public enum SectionKey { HERO, COLLECTIONS, BRANDS, PRODUCTS }
    public record Banner(@NotBlank @Size(max=64) String id,
            @NotBlank @Size(max=150) String title, @Size(max=1000) String description,
            @NotBlank @Size(max=2048) String imageUrl, @Size(max=2048) String destination,
            @NotNull Placement placement, Target target, boolean enabled, Instant startsAt, Instant endsAt) {
        public Banner { if (target == null) target = Target.BOTH; }
        public boolean eligible(Target audience, Instant now) {
            return enabled && (target == Target.BOTH || target == audience)
                    && (startsAt == null || !now.isBefore(startsAt)) && (endsAt == null || now.isBefore(endsAt));
        }
    }
    public record Section(@NotNull SectionKey key, boolean enabled) {}
    public record Document(@NotNull @Size(max=30) List<@NotNull @Valid Banner> banners,
            @NotNull @Size(min=4,max=4) List<@NotNull @Valid Section> sections,
            @NotNull @Size(max=30) List<@NotNull @Positive Long> brandIds,
            @NotNull @Size(max=30) List<@NotNull @Positive Long> collectionIds) {}
    public record Change(@Min(0) long expectedVersion, @NotNull @Valid Document document) {}
    public record Version(@Min(0) long expectedVersion) {}
    public record State(long version, Document draft, Document published) {}
    public record History(long id,long version,String action,String actor,Instant occurredAt) {}
}
