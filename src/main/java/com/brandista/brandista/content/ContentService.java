package com.brandista.brandista.content;

import static com.brandista.brandista.content.ContentModel.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.time.Instant;
import java.sql.Timestamp;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

@Service
public class ContentService {
    private final JdbcTemplate db;
    private final ObjectMapper json;
    public ContentService(JdbcTemplate db,ObjectMapper json) { this.db=db; this.json=json; }
    Document parse(String value) {
        try { return value == null ? null : json.readValue(value,Document.class); }
        catch(Exception e) { throw new IllegalStateException("Invalid stored content",e); }
    }
    String encode(Document value) {
        try { return json.writeValueAsString(value); }
        catch(Exception e) { throw new IllegalStateException("Cannot serialize content",e); }
    }
    State read(boolean lock) {
        return db.queryForObject("SELECT version,draft,published FROM app_content WHERE id=1"+(lock?" FOR UPDATE":""),
                (r,n)->new State(r.getLong(1),parse(r.getString(2)),parse(r.getString(3))));
    }
    @Transactional(readOnly=true)
    public State state() { return read(false); }
    void check(State state,long expected) {
        if(state.version()!=expected) throw new ResponseStatusException(HttpStatus.CONFLICT,"Content changed; reload before continuing");
    }
    static void require(boolean valid,String message) {
        if(!valid) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,message);
    }
    public static void destination(String value) {
        if(value==null || value.isBlank()) return;
        // A shared route vocabulary works in both the customer website and native app.
        require(value.matches("/(products(\\?categoryId=[1-9][0-9]*|\\?brandId=[1-9][0-9]*)?|brands)"),
                "Choose /products, /brands, or /products?brandId=ID or /products?categoryId=ID");
    }
    void validate(Document doc) {
        require(doc.sections().stream().map(Section::key).distinct().count()==4,"Each supported section must occur exactly once");
        require(doc.banners().stream().map(Banner::id).distinct().count()==doc.banners().size(),"Duplicate banner ID");
        for(Banner b:doc.banners()) {
            require(!b.title().contains("<") && (b.description()==null || !b.description().contains("<")),"Use plain text");
            require(b.startsAt()==null || b.endsAt()==null || b.startsAt().isBefore(b.endsAt()),"End must follow start");
            destination(b.destination());
            require(db.queryForObject("SELECT COUNT(*) FROM app_content_images WHERE url=?",Integer.class,b.imageUrl())==1,"Upload a validated image first");
        }
        references(doc.brandIds(),"brands"); references(doc.collectionIds(),"categories");
    }
    void references(List<Long> ids,String table) {
        require(ids.stream().distinct().count()==ids.size(),"Duplicate selection");
        for(Long id:ids) require(db.queryForObject("SELECT COUNT(*) FROM "+table+" WHERE id=? AND status='ACTIVE'",Integer.class,id)==1,"Select active brands and collections");
    }
    void audit(long version,String action,String actor,Document snapshot) {
        db.update("INSERT INTO app_content_history(version,action,actor,occurred_at,snapshot) VALUES(?,?,?,?,?)",
                version,action,actor,Timestamp.from(Instant.now()),encode(snapshot));
    }
    @Transactional
    public State save(Change change,String actor) {
        State old=read(true); check(old,change.expectedVersion()); validate(change.document());
        db.update("UPDATE app_content SET version=?,draft=? WHERE id=1",old.version()+1,encode(change.document()));
        audit(old.version()+1,"SAVE_DRAFT",actor,change.document()); return read(false);
    }
    @Transactional
    public State publish(long expected,String actor) {
        State old=read(true); check(old,expected); validate(old.draft());
        db.update("UPDATE app_content SET version=?,published=draft WHERE id=1",old.version()+1);
        audit(old.version()+1,"PUBLISH",actor,old.draft()); return read(false);
    }
    @Transactional
    public State rollback(long id,long expected,String actor) {
        State old=read(true); check(old,expected);
        var snapshots=db.queryForList("SELECT snapshot FROM app_content_history WHERE id=? AND action IN ('PUBLISH','ROLLBACK')",String.class,id);
        if(snapshots.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Published revision not found");
        Document restored=parse(snapshots.getFirst()); validate(restored);
        db.update("UPDATE app_content SET version=?,draft=?,published=? WHERE id=1",old.version()+1,encode(restored),encode(restored));
        audit(old.version()+1,"ROLLBACK",actor,restored); return read(false);
    }
    public List<History> history(int page) {
        require(page>=0 && page<=10000,"Invalid page");
        return db.query("SELECT id,version,action,actor,occurred_at FROM app_content_history ORDER BY id DESC LIMIT 50 OFFSET ?",
                (r,n)->new History(r.getLong(1),r.getLong(2),r.getString(3),r.getString(4),r.getTimestamp(5).toInstant()),page*50);
    }
    public Document eligible(Document document,Target target,Instant at) {
        require(target!=Target.BOTH,"Audience must be MOBILE or WEBSITE");
        if(document==null) return null;
        return new Document(document.banners().stream().filter(b->b.eligible(target,at)).toList(),document.sections(),
                active(document.brandIds(),"brands"),active(document.collectionIds(),"categories"));
    }
    List<Long> active(List<Long> ids,String table) {
        return ids.stream().filter(id->db.queryForObject("SELECT COUNT(*) FROM "+table+" WHERE id=? AND status='ACTIVE'",Integer.class,id)==1).toList();
    }
}
