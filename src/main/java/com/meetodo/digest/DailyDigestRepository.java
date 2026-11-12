package com.meetodo.digest;

import java.time.LocalDate;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DailyDigestRepository extends JpaRepository<DailyDigest, Long> {
    Optional<DailyDigest> findByDigestDate(LocalDate digestDate);
}
