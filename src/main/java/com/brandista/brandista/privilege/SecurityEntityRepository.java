package com.brandista.brandista.privilege;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.NoRepositoryBean;

/** Bulk deletes skip lifecycle security guards and therefore are never an application API. */
@NoRepositoryBean
public interface SecurityEntityRepository<T> extends JpaRepository<T, Long> {
    @Override default void deleteAllInBatch() { throw bulkDenied(); }
    @Override default void deleteAllInBatch(Iterable<T> entities) { throw bulkDenied(); }
    @Override default void deleteAllByIdInBatch(Iterable<Long> ids) { throw bulkDenied(); }
    @Override @Deprecated default void deleteInBatch(Iterable<T> entities) { throw bulkDenied(); }
    private static UnsupportedOperationException bulkDenied() {
        return new UnsupportedOperationException("Security entities require guarded per-entity mutations.");
    }
}
