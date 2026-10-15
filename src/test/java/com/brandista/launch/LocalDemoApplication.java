package com.brandista.launch;

import com.brandista.brandista.BrandistaApplication;
import com.brandista.brandista.user.*;
import com.brandista.brandista.brand.*;
import com.brandista.brandista.category.*;
import com.brandista.brandista.product.*;
import org.springframework.boot.SpringApplication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import java.math.BigDecimal;

/** Test-classpath-only launcher. Never included in the deployable application jar. */
public class LocalDemoApplication {
    public static void main(String[] args) {
        if (!"jdbc:h2:file:./target/launch-demo;MODE=MySQL;DB_CLOSE_DELAY=-1".equals(System.getenv("SPRING_DATASOURCE_URL"))
                || !"127.0.0.1".equals(System.getenv("SERVER_ADDRESS"))) {
            throw new IllegalStateException("Demo requires the isolated target/launch-demo H2 database and loopback binding");
        }
        String password = System.getenv("LOCAL_DEMO_PASSWORD");
        if (password == null || password.length() < 16) throw new IllegalStateException("Set a generated LOCAL_DEMO_PASSWORD");
        var context = SpringApplication.run(BrandistaApplication.class, args);
        new TransactionTemplate(context.getBean(PlatformTransactionManager.class)).executeWithoutResult(tx -> {
            var users = context.getBean(UserRepository.class);
            var encoder = context.getBean(PasswordEncoder.class);
            for (var role : UserRole.values()) {
                String email = role.name().toLowerCase() + "@demo.brandista.test";
                if (users.findByEmail(email).isEmpty()) users.save(new User(email, encoder.encode(password), role, AccountStatus.ENABLED));
            }
            var brands = context.getBean(BrandRepository.class);
            if (brands.findAll().stream().noneMatch(b -> "sample-studio".equals(b.getSlug()))) {
                var brand = brands.save(new Brand("SAMPLE Studio", "sample-studio", "Fictional demonstration brand; not a signed partner.", null));
                var category = context.getBean(CategoryRepository.class).save(new Category("Sample clothing", "sample-clothing", "Synthetic demo catalogue"));
                var product = new Product("SAMPLE Linen Shirt", "sample-linen-shirt", "Synthetic demo product. CSS illustration only; no physical inventory or purchasing.", brand, category, new BigDecimal("2499.95"), ProductAvailability.AVAILABLE);
                product.publish();
                context.getBean(ProductRepository.class).save(product);
            }
        });
        System.out.println("LOCAL H2 DEMO READY — sample data only; no checkout, stock counts or payments");
    }
}
