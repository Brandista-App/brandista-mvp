package com.brandista.brandista.onboarding;

import com.fasterxml.jackson.databind.*;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;

@Component
public class ResendMerchantEmail implements MerchantEmailProvider {
    private final Environment env;
    private final ObjectMapper json;
    private final URI endpoint;
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
    @org.springframework.beans.factory.annotation.Autowired
    public ResendMerchantEmail(Environment env,ObjectMapper json){this(env,json,URI.create("https://api.resend.com"));}
    ResendMerchantEmail(Environment env,ObjectMapper json,URI endpoint){this.env=env;this.json=json;this.endpoint=endpoint;}
    private JsonNode call(String method,String path,Object body,String key){
        try {
            var request=HttpRequest.newBuilder(endpoint.resolve(path)).timeout(Duration.ofSeconds(15))
                .header("Authorization","Bearer "+env.getRequiredProperty("brandista.invitations.resend-api-key"));
            if(key!=null)request.header("Idempotency-Key",key);
            if(body!=null)request.header("Content-Type","application/json");
            request.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
            var response=http.send(request.build(),HttpResponse.BodyHandlers.ofString());
            if(response.statusCode()<200||response.statusCode()>=300)throw new IllegalStateException();
            return json.readTree(response.body());
        }catch(Exception failed){if(failed instanceof InterruptedException)Thread.currentThread().interrupt();throw new IllegalStateException("Email provider request unavailable.");}
    }
    public String send(String key,String email,String brand,String token,String expires){
        String sender=env.getRequiredProperty("brandista.invitations.sender-email");
        String domainId=env.getRequiredProperty("brandista.invitations.resend-domain-id");
        if(!domainId.matches("[A-Za-z0-9-]{1,128}"))throw new IllegalStateException("Invalid sender domain ID.");
        JsonNode domain=call("GET","/domains/"+domainId,null,null);
        if(!"verified".equals(domain.path("status").asText()) || !sender.endsWith("@"+domain.path("name").asText()) || sender.contains("\n") || sender.contains("\r"))
            throw new IllegalStateException("Verified sender domain required.");
        String origin=env.getRequiredProperty("brandista.invitations.registration-origin");
        URI uri=URI.create(origin);
        if(!"https".equals(uri.getScheme())||uri.getHost()==null||uri.getUserInfo()!=null||uri.getQuery()!=null||uri.getFragment()!=null||!uri.getPath().isEmpty())
            throw new IllegalStateException("Registration origin must be an HTTPS origin without a trailing slash.");
        String link=origin+"/accept-invitation#token="+token;
        String text="Welcome to Brandista\n\nYour application for "+brand+" has been approved.\n\nCreate your merchant account or sign in to your existing account and accept the merchant terms:\n"+link+
            "\n\nThis single-use invitation expires at "+expires+" UTC (48 hours after issue). It grants access only to your approved brand. Never share this link.\n\nIf you did not apply, ignore this email.\n\nBrandista Partnerships\nMany brands. One destination.";
        var body=Map.of("from","Brandista <"+sender+">","to",List.of(email),"subject","Your Brandista merchant application is approved","text",text,
            "html","<div style=\"font-family:Arial,sans-serif;max-width:600px;margin:auto;line-height:1.7;color:#241f1a\"><h1>BRANDISTA</h1><h2>Welcome to your next chapter.</h2><p>Your application for <strong>"+escape(brand)+"</strong> has been approved.</p><p>Create your account and accept the merchant terms to join your assigned brand workspace.</p><p><a style=\"background:#30271f;color:white;padding:14px 22px;text-decoration:none\" href=\""+link+"\">Activate merchant account</a></p><p>This single-use link expires at "+escape(expires)+" UTC. Keep it private.</p><p>If you did not apply, you can ignore this email.</p><hr><p>Brandista Partnerships<br>Many brands. One destination.</p></div>");
        String id=call("POST","/emails",body,key).path("id").asText();
        if(!id.matches("[A-Za-z0-9-]{1,128}"))throw new IllegalStateException("Email provider did not confirm acceptance.");
        return id;
    }
    public String status(String id){
        if(!id.matches("[A-Za-z0-9-]{1,128}"))throw new IllegalArgumentException();
        return switch(call("GET","/emails/"+id,null,null).path("last_event").asText()){
            case "delivered","opened","clicked" -> "DELIVERED";
            case "bounced","complained","failed","suppressed" -> "FAILED";
            default -> "PROVIDER_ACCEPTED";
        };
    }
    private static String escape(String value){return value.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("'","&#39;");}
}
