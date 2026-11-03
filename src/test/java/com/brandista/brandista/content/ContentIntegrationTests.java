package com.brandista.brandista.content;
import static com.brandista.brandista.content.ContentModel.*;
import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.*;
import org.springframework.mock.web.MockMultipartFile;
import com.brandista.brandista.user.*;
import com.brandista.brandista.privilege.*;
import org.springframework.security.crypto.password.PasswordEncoder;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.*;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.io.ByteArrayOutputStream;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
 "spring.datasource.url=jdbc:h2:mem:content-test;MODE=MySQL;DB_CLOSE_DELAY=-1","spring.datasource.username=sa","spring.datasource.password="})
class ContentIntegrationTests {
 @Autowired ContentService service;
 @Autowired JdbcTemplate db;
 @Autowired TestRestTemplate http;
 @Autowired UserRepository users;
 @Autowired PlatformGrantRepository grants;
 @Autowired PasswordEncoder passwords;
 String image="https://images.example.test/app-content/test.png";
 @BeforeEach void reset() {
  db.update("UPDATE app_content SET version=0,published=NULL WHERE id=1");
  db.update("DELETE FROM app_content_history"); db.update("DELETE FROM app_content_images");
  db.update("INSERT INTO app_content_images(url,actor,created_at) VALUES(?,'test',CURRENT_TIMESTAMP)",image);
 }
 Document doc(List<Banner> banners) { return new Document(banners,Arrays.stream(SectionKey.values()).map(k->new Section(k,true)).toList(),List.of(),List.of()); }
 Banner banner(String id,Target target,boolean enabled,Instant start,Instant end) { return new Banner(id,"Promotion","Plain text",image,"/products",Placement.HERO,target,enabled,start,end); }
 @Test void draftIsolationPublishConcurrencyAndRollbackAreAtomicAndAudited() {
  var original=doc(List.of(banner("first",null,true,null,null)));
  assertThat(original.banners().getFirst().target()).isEqualTo(Target.BOTH);
  service.save(new Change(0,original),"101"); assertThat(service.state().published()).isNull();
  assertThatThrownBy(()->service.publish(0,"101")).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
  service.publish(1,"101"); var release=service.history(0).getFirst();
  service.save(new Change(2,doc(List.of())),"102"); assertThat(service.state().published()).isEqualTo(original);
  service.publish(3,"102"); assertThat(service.state().published().banners()).isEmpty();
  service.rollback(release.id(),4,"103"); assertThat(service.state().published()).isEqualTo(original);
  assertThat(service.history(0)).hasSize(5);assertThat(service.history(0).getFirst().actor()).isEqualTo("103");
 }
 @Test void audienceDatesAndUnpublishingHavePreciseBoundaries() {
  Instant now=Instant.parse("2026-11-02T12:00:00Z");
  Document d=doc(List.of(banner("both",Target.BOTH,true,now,now.plusSeconds(10)),banner("mobile",Target.MOBILE,true,null,null),banner("web",Target.WEBSITE,true,null,null),banner("draft",Target.BOTH,false,null,null),banner("future",Target.BOTH,true,now.plusSeconds(1),null),banner("expired",Target.BOTH,true,null,now)));
  assertThat(service.eligible(d,Target.MOBILE,now).banners()).extracting(Banner::id).containsExactly("both","mobile");
  assertThat(service.eligible(d,Target.WEBSITE,now).banners()).extracting(Banner::id).containsExactly("both","web");
  assertThat(service.eligible(d,Target.WEBSITE,now.plusSeconds(10)).banners()).extracting(Banner::id).containsExactly("web","future");
  assertThatThrownBy(()->service.eligible(d,Target.BOTH,now)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
 }
 @Test void unsafeDestinationsAndUnregisteredImagesAreRejected() {
  for(String url:List.of("javascript:alert(1)","//evil.test","https://evil.test","/products?brandId=1&x=2","/products%0a"))assertThatThrownBy(()->ContentService.destination(url)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
  Document d=doc(List.of(new Banner("bad","Title","", "https://untrusted.test/img.png",null,Placement.HERO,Target.BOTH,true,null,null)));
  assertThatThrownBy(()->service.save(new Change(0,d),"1")).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
  assertThat(service.state().version()).isZero();assertThat(service.history(0)).isEmpty();
 }
 @Test void imageBytesAreDecodedAndReencodedAndSvgIsRejected() throws Exception {
  var out=new ByteArrayOutputStream();ImageIO.write(new BufferedImage(4,4,BufferedImage.TYPE_INT_RGB),"png",out);
  byte[] clean=ContentImages.sanitize(new MockMultipartFile("file","image.png","image/png",out.toByteArray()));
  assertThat(ImageIO.read(new java.io.ByteArrayInputStream(clean)).getWidth()).isEqualTo(4);
  for(String type:List.of("image/svg+xml","image/png"))assertThatThrownBy(()->ContentImages.sanitize(new MockMultipartFile("file","bad",type,"<svg onload='alert(1)'/>".getBytes()))).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
 }
 String login(boolean superAdmin) {
  var user=users.saveAndFlush(new User(UUID.randomUUID()+"@example.test",passwords.encode("Content-Tests-2026!"),UserRole.CUSTOMER,AccountStatus.ENABLED));
  if(superAdmin)grants.saveAndFlush(new PlatformGrant(user,PlatformGrantType.SUPER_ADMIN));
  return http.postForEntity("/api/v1/auth/login",Map.of("email",user.getEmail(),"password","Content-Tests-2026!"),JsonNode.class).getBody().path("accessToken").asText();
 }
 ResponseEntity<String> call(HttpMethod method,String path,String token,Object body) {
  var headers=new HttpHeaders();if(token!=null)headers.setBearerAuth(token);headers.setContentType(MediaType.APPLICATION_JSON);
  return http.exchange(path,method,new HttpEntity<>(body,headers),String.class);
 }
 @Test void httpRequiresCurrentSuperAdminAndPublicApiNeverReturnsDrafts() {
  String base="/api/v1/super-admin/content";
  assertThat(call(HttpMethod.GET,base,null,null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
  assertThat(call(HttpMethod.PUT,base,login(false),new Change(0,doc(List.of()))).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
  String token=login(true);
  assertThat(call(HttpMethod.PUT,base,token,new Change(0,doc(List.of(banner("draft-only",Target.BOTH,true,null,null))))).getStatusCode()).isEqualTo(HttpStatus.OK);
  assertThat(Objects.toString(http.getForEntity("/api/v1/content/homepage?target=WEBSITE",String.class).getBody(),"")).doesNotContain("draft-only");
  assertThat(call(HttpMethod.POST,base+"/publish",token,new Version(1)).getStatusCode()).isEqualTo(HttpStatus.OK);
  assertThat(http.getForEntity("/api/v1/content/homepage?target=WEBSITE",String.class).getBody()).contains("draft-only");
  assertThat(call(HttpMethod.POST,"/api/v1/content/homepage?target=WEBSITE",token,null).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
 }
}
