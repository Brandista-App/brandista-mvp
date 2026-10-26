package com.brandista.brandista.product;

import com.brandista.brandista.brand.Brand;
import com.brandista.brandista.category.Category;
import com.brandista.brandista.product.api.ProductSort;
import com.brandista.brandista.product.service.ProductService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties={
        "spring.datasource.url=jdbc:h2:mem:search-query;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.jpa.properties.hibernate.generate_statistics=true",
        "spring.jpa.properties.hibernate.session_factory.statement_inspector=com.brandista.brandista.product.ProductSearchQueryIntegrationTests$SqlCapture"})
@Transactional
class ProductSearchQueryIntegrationTests {
    public static class SqlCapture implements StatementInspector {
        static final List<String> sql=new ArrayList<>();
        @Override public String inspect(String statement) { sql.add(statement);return statement; }
    }
    @Autowired EntityManager entityManager;
    @Autowired EntityManagerFactory factory;
    @Autowired ProductService products;

    @Test void searchFetchesReferencesOnceWithDatabasePaginationAndSeparateCount() {
        var brand=new Brand("Query brand","query-brand",null,null);
        var category=new Category("Query category","query-category",null);
        entityManager.persist(brand);entityManager.persist(category);
        for(int i=0;i<25;i++) {
            var product=new Product("Synthetic "+i,"query-product-"+i,"literal %_! description",brand,category,
                    BigDecimal.TEN,ProductAvailability.AVAILABLE);
            product.publish();entityManager.persist(product);
        }
        entityManager.flush();entityManager.clear();
        var statistics=factory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();SqlCapture.sql.clear();
        var page=products.listPublic(1,10,"synthetic",null,null,ProductSort.PRICE_ASC);
        assertThat(page.totalElements()).isEqualTo(25);
        assertThat(page.totalPages()).isEqualTo(3);
        assertThat(page.content()).hasSize(10).allSatisfy(product -> {
            assertThat(product.brandName()).isEqualTo("Query brand");
            assertThat(product.categoryName()).isEqualTo("Query category");
        });
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(2);
        var dataSql=SqlCapture.sql.stream().filter(sql->sql.startsWith("select ")&&!sql.startsWith("select count(")).findFirst().orElseThrow();
        assertThat(dataSql.split(" join brands ",-1).length-1).isEqualTo(1);
        assertThat(dataSql.split(" join categories ",-1).length-1).isEqualTo(1);
        assertThat(dataSql).contains("offset ? rows fetch first ? rows only");
        assertThat(page.content().stream().map(product->product.id()).toList()).isSorted();
    }
}
