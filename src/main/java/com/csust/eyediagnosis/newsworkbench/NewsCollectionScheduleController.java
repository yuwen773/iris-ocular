package com.csust.eyediagnosis.newsworkbench;

import com.csust.eyediagnosis.annotation.AuthCheck;
import com.csust.eyediagnosis.common.BaseResponse;
import com.csust.eyediagnosis.common.ResultUtils;
import com.csust.eyediagnosis.constant.AuthConstant;
import com.csust.eyediagnosis.newsingestion.NewsCollectionScheduleStatusService;
import com.csust.eyediagnosis.newsingestion.NewsCollectionScheduleStatusService.ScheduleStatus;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/news-workbench/scheduling")
@RequiredArgsConstructor
public class NewsCollectionScheduleController {

    private final NewsCollectionScheduleStatusService scheduleStatus;

    @GetMapping
    @AuthCheck(mustRole = AuthConstant.ADMIN_ROLE)
    @Operation(summary = "查看自动资讯采集调度与 Worker 领取状态")
    public ResponseEntity<BaseResponse<ScheduleStatus>> status() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ResultUtils.success(scheduleStatus.status()));
    }
}
