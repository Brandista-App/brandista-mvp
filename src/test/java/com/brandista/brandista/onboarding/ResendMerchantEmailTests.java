package com.brandista.brandista.onboarding;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;

class ResendMerchantEmailTests {
    @Test void verifiesDomainSendsBrandedEmailWithIdempotencyAndSeparatesDeliveryStatus() throws Exception {
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        AtomicReference<String> body=new AtomicReference<>();
        AtomicReference<String> idempotency=new AtomicReference<>();
        AtomicReference<String> domainStatus=new AtomicReference<>("verified");
        AtomicReference<String> domainName=new AtomicReference<>("example.invalid");
        server.createContext("/domains/domain-test",exchange->{
            byte[] response=("{\"status\":\""+domainStatus.get()+"\",\"name\":\""+domainName.get()+"\"}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200,response.length);exchange.getResponseBody().write(response);exchange.close();
        });
        server.createContext("/emails",exchange->{
            String result;
            if(exchange.getRequestMethod().equals("POST")){
                body.set(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
                idempotency.set(exchange.getRequestHeaders().getFirst("Idempotency-Key"));result="{\"id\":\"synthetic-email-id\"}";
            }else result="{\"last_event\":\"delivered\"}";
            byte[] response=result.getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,response.length);exchange.getResponseBody().write(response);exchange.close();
        });
        server.start();
        try {
            var environment=new MockEnvironment().withProperty("brandista.invitations.resend-api-key","synthetic-key")
                .withProperty("brandista.invitations.sender-email","marketing@example.invalid")
                .withProperty("brandista.invitations.resend-domain-id","domain-test")
                .withProperty("brandista.invitations.registration-origin","https://admin.example.invalid");
            var provider=new ResendMerchantEmail(environment,new ObjectMapper(),URI.create("http://127.0.0.1:"+server.getAddress().getPort()));
            String token="a".repeat(43);
            assertThat(provider.send("synthetic-idempotency-key","recipient@example.invalid","TSC <synthetic>",token,"2026-11-05T10:00:00")).isEqualTo("synthetic-email-id");
            assertThat(idempotency.get()).isEqualTo("synthetic-idempotency-key");
            var message=new ObjectMapper().readTree(body.get());
            assertThat(message.path("from").asText()).isEqualTo("Brandista <marketing@example.invalid>");
            assertThat(message.path("html").asText()).contains("TSC &lt;synthetic&gt;","https://admin.example.invalid/accept-invitation#token="+token,"Activate merchant account");
            assertThat(message.path("to").get(0).asText()).isEqualTo("recipient@example.invalid");
            assertThat(provider.status("synthetic-email-id")).isEqualTo("DELIVERED");
            domainName.set("mail.example.invalid");
            assertThatThrownBy(()->provider.send("blocked","recipient@example.invalid","Synthetic",token,"2026-11-05")).hasMessageContaining("Verified sender domain");
            domainName.set("example.invalid");
            domainStatus.set("pending");
            assertThatThrownBy(()->provider.send("blocked","recipient@example.invalid","Synthetic",token,"2026-11-05")).hasMessageContaining("Verified sender domain");
            environment.setProperty("brandista.invitations.registration-origin","http://admin.example.invalid");domainStatus.set("verified");
            assertThatThrownBy(()->provider.send("blocked","recipient@example.invalid","Synthetic",token,"2026-11-05")).hasMessageContaining("HTTPS origin");
        }finally{server.stop(0);}
    }
}
