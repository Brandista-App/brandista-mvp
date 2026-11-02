package com.brandista.brandista.content;

import static com.brandista.brandista.content.ContentService.require;
import java.io.*;
import java.net.URI;
import java.time.Instant;
import java.sql.Timestamp;
import java.util.*;
import javax.imageio.ImageIO;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import software.amazon.awssdk.auth.credentials.*;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import java.time.Duration;

@Service
public class ContentImages {
    private final Environment env;
    private final JdbcTemplate db;
    public ContentImages(Environment env,JdbcTemplate db) { this.env=env; this.db=db; }
    static byte[] sanitize(MultipartFile file) throws IOException {
        require(file.getSize()>0 && file.getSize()<=5*1024*1024,"Image must be 5 MiB or smaller");
        require(Set.of("image/png","image/jpeg").contains(Objects.toString(file.getContentType(),"")),"Use PNG or JPEG");
        try(var input=ImageIO.createImageInputStream(new ByteArrayInputStream(file.getBytes()))) {
            var readers=ImageIO.getImageReaders(input);
            require(readers.hasNext(),"Invalid image");
            var reader=readers.next();
            try {
                reader.setInput(input);
                String format=reader.getFormatName();
                require(format.equalsIgnoreCase("png") || format.equalsIgnoreCase("jpeg"),"Use PNG or JPEG");
                require(reader.getWidth(0)>0 && reader.getHeight(0)>0 && (long)reader.getWidth(0)*reader.getHeight(0)<=16_000_000,"Image dimensions too large");
                var image=reader.read(0);
                var bytes=new ByteArrayOutputStream();
                // Re-encoding strips metadata, appended files, and executable/polyglot bytes.
                ImageIO.write(image,"png",bytes);
                require(bytes.size()<=12*1024*1024,"Decoded image too large");
                return bytes.toByteArray();
            } finally { reader.dispose(); }
        }
    }
    String setting(String name) {
        String value=env.getProperty(name,"");
        if(value.isBlank()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Image storage is not configured");
        return value;
    }
    public Map<String,String> upload(MultipartFile file,String actor) throws IOException {
        byte[] bytes=sanitize(file);
        String bucket=setting("CONTENT_S3_BUCKET"),base=setting("CONTENT_IMAGE_BASE_URL").replaceAll("/+$","");
        var baseUri=URI.create(base);
        require("https".equals(baseUri.getScheme()) && baseUri.getHost()!=null && baseUri.getUserInfo()==null && baseUri.getQuery()==null && baseUri.getFragment()==null,"Image base must use HTTPS without credentials, query or fragment");
        var builder=S3Client.builder().region(Region.of(setting("CONTENT_S3_REGION")))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(setting("CONTENT_S3_ACCESS_KEY"),setting("CONTENT_S3_SECRET_KEY"))))
                .httpClientBuilder(UrlConnectionHttpClient.builder().connectionTimeout(Duration.ofSeconds(5)).socketTimeout(Duration.ofSeconds(20)))
                .overrideConfiguration(c->c.apiCallTimeout(Duration.ofSeconds(30)));
        String endpoint=env.getProperty("CONTENT_S3_ENDPOINT","");
        if(!endpoint.isBlank()) {
            require(URI.create(endpoint).getScheme().equals("https"),"Storage endpoint must use HTTPS");
            builder.endpointOverride(URI.create(endpoint)).forcePathStyle(true);
        }
        require(base.length()<600,"Image base is too long");
        String key="app-content/"+UUID.randomUUID()+".png";
        try(var s3=builder.build()) {
            s3.putObject(r->r.bucket(bucket).key(key).contentType("image/png").cacheControl("public,max-age=31536000,immutable"),RequestBody.fromBytes(bytes));
        } catch(Exception e) { throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Image storage unavailable"); }
        String url=base+"/"+key;
        db.update("INSERT INTO app_content_images(url,actor,created_at) VALUES(?,?,?)",url,actor,Timestamp.from(Instant.now()));
        return Map.of("url",url);
    }
}
