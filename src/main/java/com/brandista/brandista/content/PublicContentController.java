package com.brandista.brandista.content;
import static com.brandista.brandista.content.ContentModel.*;
import java.time.Instant;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
public class PublicContentController {
    private final ContentService service;
    public PublicContentController(ContentService service) { this.service=service; }
    @GetMapping("/api/v1/content/homepage")
    public ResponseEntity<Document> homepage(@RequestParam Target target) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.eligible(service.state().published(),target,Instant.now()));
    }
}
