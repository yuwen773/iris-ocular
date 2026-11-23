package com.csust.eyediagnosis.controller;

import com.csust.eyediagnosis.annotation.AuthCheck;
import com.csust.eyediagnosis.common.BaseResponse;
import com.csust.eyediagnosis.common.ResultUtils;
import com.csust.eyediagnosis.doctorreview.DoctorReviewService;
import com.csust.eyediagnosis.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/doctor-reviews")
public class DoctorReviewController {
    private final DoctorReviewService reviews;
    private final UserService users;
    private final com.csust.eyediagnosis.diagnosistask.DiagnosisTaskService tasks;

    public DoctorReviewController(DoctorReviewService reviews, UserService users,
            com.csust.eyediagnosis.diagnosistask.DiagnosisTaskService tasks) {
        this.reviews = reviews; this.users = users; this.tasks = tasks;
    }

    public record Assignment(String taskId, Long doctorId) {}

    @ModelAttribute
    public void preventCaching(jakarta.servlet.http.HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
    }

    @PostMapping("/assign")
    @AuthCheck
    public BaseResponse<DoctorReviewService.Summary> assign(@RequestBody Assignment assignment, HttpServletRequest request) {
        var review = reviews.assign(users.getLoginUser(request), assignment.taskId(), assignment.doctorId());
        return ResultUtils.success(new DoctorReviewService.Summary(review.reviewId(), review.taskId(), review.status(), review.assignedAt()));
    }

    @GetMapping
    @AuthCheck
    public BaseResponse<List<DoctorReviewService.Summary>> list(HttpServletRequest request) {
        return ResultUtils.success(reviews.list(users.getLoginUser(request)));
    }

    @GetMapping("/{id}")
    @AuthCheck
    public BaseResponse<DoctorReviewService.Detail> detail(@PathVariable String id, HttpServletRequest request) {
        return ResultUtils.success(reviews.detail(users.getLoginUser(request), id));
    }

    @PostMapping("/{id}/decision")
    @AuthCheck
    public BaseResponse<DoctorReviewService.Detail> decide(@PathVariable String id,
            @RequestBody DoctorReviewService.Decision decision, HttpServletRequest request) {
        return ResultUtils.success(reviews.decide(users.getLoginUser(request), id, decision));
    }

    @GetMapping("/{id}/image")
    @AuthCheck
    public ResponseEntity<byte[]> image(@PathVariable String id, HttpServletRequest request) {
        var user = users.getLoginUser(request);
        var review = reviews.detail(user, id);
        var image = tasks.readImage(review.taskId());
        return ResponseEntity.ok().header("X-Content-Type-Options", "nosniff")
                .header("Content-Disposition", "inline; filename=fundus." + image.format())
                .contentType(org.springframework.http.MediaType.parseMediaType(
                        image.format().equals("png") ? "image/png" : "image/jpeg"))
                .body(image.bytes());
    }

    @ExceptionHandler(com.csust.eyediagnosis.diagnosistask.DiagnosisTaskService.ImageUnavailable.class)
    public ResponseEntity<BaseResponse<?>> imageUnavailable(
            com.csust.eyediagnosis.diagnosistask.DiagnosisTaskService.ImageUnavailable error) {
        return ResponseEntity.status(503).contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .body(ResultUtils.error(50300, error.getMessage()));
    }

    @ExceptionHandler(com.csust.eyediagnosis.collaboration.CollaborationRunService.Fault.class)
    public ResponseEntity<BaseResponse<?>> imageFault(com.csust.eyediagnosis.collaboration.CollaborationRunService.Fault error) {
        return ResponseEntity.status(error.status()).contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .body(ResultUtils.error(error.status() * 100, error.getMessage()));
    }

    @ExceptionHandler(DoctorReviewService.ReviewConflict.class)
    public ResponseEntity<BaseResponse<?>> conflict(DoctorReviewService.ReviewConflict error) {
        return ResponseEntity.status(409).body(ResultUtils.error(40900, error.getMessage()));
    }
}
