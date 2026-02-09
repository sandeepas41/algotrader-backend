package com.algotrader.morph;

import com.algotrader.domain.enums.MorphPlanStatus;
import com.algotrader.entity.MorphPlanEntity;
import com.algotrader.repository.jpa.MorphPlanJpaRepository;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;

/**
 * Recovers incomplete morph operations on application startup.
 *
 * <p>Checks for morph plans in EXECUTING status, which indicates the
 * application crashed mid-morph. These are marked as PARTIALLY_DONE
 * and logged for manual intervention.
 *
 * <p>Full automated recovery (re-executing remaining steps) is a
 * future enhancement -- for now, detection and alerting is sufficient
 * since partially executed morphs are rare and require human review.
 */
@Component
public class MorphRecoveryService implements ApplicationListener<ApplicationReadyEvent> {

    private static final Logger log = LoggerFactory.getLogger(MorphRecoveryService.class);

    private final MorphPlanJpaRepository morphPlanJpaRepository;

    public MorphRecoveryService(MorphPlanJpaRepository morphPlanJpaRepository) {
        this.morphPlanJpaRepository = morphPlanJpaRepository;
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        List<MorphPlanEntity> incomplete = morphPlanJpaRepository.findByStatus(MorphPlanStatus.EXECUTING);

        if (!incomplete.isEmpty()) {
            log.warn("Found {} incomplete morph plans from previous run", incomplete.size());

            for (MorphPlanEntity plan : incomplete) {
                log.warn(
                        "Incomplete morph plan: id={}, source={}, status={}",
                        plan.getId(),
                        plan.getSourceStrategyId(),
                        plan.getStatus());

                // Mark as PARTIALLY_DONE for manual review
                plan.setStatus(MorphPlanStatus.PARTIALLY_DONE);
                plan.setErrorMessage("Application restarted during morph execution. Manual review required.");
                morphPlanJpaRepository.save(plan);

                // #TODO: Implement automated recovery by re-reading planDetails JSON
                //        and resuming from the last completed step
            }
        }
    }
}
