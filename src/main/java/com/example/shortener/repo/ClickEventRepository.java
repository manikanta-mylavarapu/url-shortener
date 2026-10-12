package com.example.shortener.repo;

import com.example.shortener.domain.ClickEvent;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ClickEventRepository extends JpaRepository<ClickEvent, Long> {

    @Query("select count(distinct c.visitorHash) from ClickEvent c where c.code = :code")
    long countUniqueVisitors(@Param("code") String code);

    @Query("select new com.example.shortener.repo.DayCount(c.clickDay, count(c)) "
            + "from ClickEvent c where c.code = :code group by c.clickDay order by c.clickDay")
    List<DayCount> clicksPerDay(@Param("code") String code);

    @Query("select new com.example.shortener.repo.ReferrerCount(c.referrerHost, count(c)) "
            + "from ClickEvent c where c.code = :code group by c.referrerHost order by count(c) desc, c.referrerHost")
    List<ReferrerCount> topReferrers(@Param("code") String code, Pageable limit);
}
