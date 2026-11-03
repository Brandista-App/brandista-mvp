package com.brandista.brandista.content;

import static com.brandista.brandista.content.ContentModel.*;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.*;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/super-admin/content")
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class ContentController {
    private final ContentService service;
    private final ContentImages images;
    public ContentController(ContentService service,ContentImages images) { this.service=service; this.images=images; }
    @GetMapping public State state() { return service.state(); }
    @PutMapping public State save(@Valid @RequestBody Change change,Authentication actor) { return service.save(change,actor.getName()); }
    @PostMapping("/publish") public State publish(@Valid @RequestBody Version version,Authentication actor) { return service.publish(version.expectedVersion(),actor.getName()); }
    @GetMapping("/history") public List<History> history(@RequestParam(defaultValue="0") int page) { return service.history(page); }
    @PostMapping("/history/{id}/rollback") public State rollback(@PathVariable long id,@Valid @RequestBody Version version,Authentication actor) { return service.rollback(id,version.expectedVersion(),actor.getName()); }
    @GetMapping("/preview") public ResponseEntity<Document> preview(@RequestParam Target target,@RequestParam(required=false) Instant at) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.eligible(service.state().draft(),target,at==null?Instant.now():at));
    }
    @PostMapping(value="/images",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String,String> image(@RequestPart MultipartFile file,Authentication actor) throws java.io.IOException { return images.upload(file,actor.getName()); }
    @ExceptionHandler(java.io.IOException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String,String> invalidImage() { return Map.of("message","Image could not be decoded. Use a valid PNG or JPEG."); }
}
