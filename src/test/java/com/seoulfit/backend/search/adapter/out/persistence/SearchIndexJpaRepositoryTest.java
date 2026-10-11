package com.seoulfit.backend.search.adapter.out.persistence;

import com.seoulfit.backend.search.domain.PoiSearchIndex;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@ActiveProfiles("test")
@DisplayName("SearchIndexJpaRepository bulk delete 및 유니크 제약 테스트")
class SearchIndexJpaRepositoryTest {

    @Autowired
    private SearchIndexJpaRepository searchIndexJpaRepository;

    @Autowired
    private TestEntityManager entityManager;

    @Test
    @DisplayName("refTable별 bulk delete는 해당 테이블 행만 제거한다")
    void deleteAllByRefTable_removesOnlyMatchingRefTable() {
        searchIndexJpaRepository.save(new PoiSearchIndex("도서관 A", "주소", null, null, "libraries", 1L));
        searchIndexJpaRepository.save(new PoiSearchIndex("공원 A", "주소", null, null, "park", 2L));
        entityManager.flush();
        entityManager.clear();

        searchIndexJpaRepository.deleteAllByRefTable("libraries");
        entityManager.flush();
        entityManager.clear();

        assertThat(searchIndexJpaRepository.findAll()).hasSize(1);
        assertThat(searchIndexJpaRepository.findAll().get(0).getRefTable()).isEqualTo("park");
    }

    @Test
    @DisplayName("동일 ref_table·ref_id 중복 저장 시 유니크 제약으로 실패한다")
    void uniqueConstraint_rejectsDuplicateRefTableAndRefId() {
        searchIndexJpaRepository.save(new PoiSearchIndex("첫 번째", "주소", null, null, "libraries", 99L));
        entityManager.flush();

        assertThatThrownBy(() -> {
            searchIndexJpaRepository.save(new PoiSearchIndex("두 번째", "주소", null, null, "libraries", 99L));
            entityManager.flush();
        }).isInstanceOf(DataIntegrityViolationException.class);
    }
}
