package com.csust.eyediagnosis.manager;

import cn.hutool.core.date.DateUtil;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.NumberUtil;
import cn.hutool.core.util.RandomUtil;
import com.csust.eyediagnosis.config.CosClientConfig;
import com.csust.eyediagnosis.constant.FileConstant;
import com.csust.eyediagnosis.exception.BusinessException;
import com.csust.eyediagnosis.exception.ErrorCode;
import com.csust.eyediagnosis.exception.ThrowUtils;
import com.csust.eyediagnosis.model.dto.file.UploadPictureDetail;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import jakarta.annotation.Resource;
import java.io.File;
import java.util.Date;

import static com.csust.eyediagnosis.constant.FileConstant.*;

/**
 * @ClassNameFileManager
 * @Description 文件上传统一的方法
 * @Author钰玟
 * @Date2025/4/17 下午3:11
 * @Version 1.0
 **/

@Service
@Slf4j
public class FileManager {

    @Resource
    private CosClientConfig cosClientConfig;

    @Resource
    private ImageArchive imageArchive;

    /**
     * 上传图片
     * @param multipartFile 文件
     * @param uploadPathPrefix 上传路径前缀
     * @return 图片详细信息
     */
    public UploadPictureDetail uploadPicture(MultipartFile multipartFile,String uploadPathPrefix) {
        //校验图片
        validPicture(multipartFile);
        //图片上传地址的处理
        String uuid = RandomUtil.randomNumbers(16);
        //文件图片名称
        String originalFilename = multipartFile.getOriginalFilename();
        //           DateUtil.formatDateTime(new Date()) 创建日期  uuid ：uuid  FileUtil.getSuffix(originalFilename))：后缀名 例如 : .jpg  .png
        //           DateUtil.formatDate(new Date()) 创建日期  uuid ：uuid  FileUtil.getSuffix(originalFilename))：后缀名 例如 : .jpg  .png
        String uploadFileName = String.format("%s_%s.%s", DateUtil.formatDate(new Date()), uuid, FileUtil.getSuffix(originalFilename));
        String uploadPath = String.format("/%s/%s", uploadPathPrefix, uploadFileName);
        File file = null;
        try {
            //创建临时文件
            file = File.createTempFile(uploadPath,null);
            multipartFile.transferTo(file);
            //上传图片（写失败时 ImageArchive 已经删过这个 key）
            ImageArchive.StoredPicture stored = imageArchive.storePicture(uploadPath, file);
            //封装返回结果
            UploadPictureDetail uploadPictureDetail = new UploadPictureDetail();
            //宽高比例
            double picScale = NumberUtil.round(stored.width()*1.0/stored.height(),2).doubleValue();
            //图片名称
            uploadPictureDetail.setPicWidth(stored.width());
            uploadPictureDetail.setPicHeight(stored.height());
            uploadPictureDetail.setPicScale(picScale);
            uploadPictureDetail.setPicFormat(stored.format());
            uploadPictureDetail.setPicSize(FileUtil.size(file));
            //拼接图片访问地址
            uploadPictureDetail.setUrl(cosClientConfig.getHost()+"/"+uploadPath);
            // 对象 key 一并交给调用方，落库失败时才能精确删除这一个对象
            uploadPictureDetail.setObjectKey(uploadPath);
            return uploadPictureDetail;
        } catch (Exception e) {
            // 覆盖 ImageArchive 没管到的那一段：对象已经写好，但后面的封装（size / host）
            // 失败。storePicture 在**写入本身**失败时已删过一次，这里再删一次是幂等的
            // （COS 对不存在的 key 删除成功），换来的是「任何失败都不留孤儿」。
            // public/ 前缀没有对账器，漏掉就是一张只能人工排查的原图。
            log.error("图片上传到对象存储失败，清理可能已写入的对象: key={}", uploadPath, e);
            imageArchive.discardQuietly(uploadPath);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, FileConstant.FAIL_UPLOAD);
        }finally{
            this.deleteTemFile(file);
        }
    }

    /**
     * 补偿删除：对象已写入存储、但后续数据库落库失败时，删除这一个对象。
     *
     * <p>只接受本类刚上传返回的 key（调用方不得用请求参数拼 key）。
     * 删除失败不抛异常——落库失败才是主因，补偿失败记日志。
     *
     * <p>如实说明：目前**没有**自动对账会扫这些对象。仓库里唯一的对账器
     * （{@code UploadCompensationService}）只扫 {@code agent-chat/} 前缀，
     * 判读影像用的 {@code public/{userId}/} 前缀属于「只记日志、人工排查」的遗留面。
     *
     * @param objectKey {@link #uploadPicture} 返回的 key
     */
    public void deleteUploadedPictureQuietly(String objectKey) {
        imageArchive.discardQuietly(objectKey);
    }

    /**
     * 校验图片文件是否合法
     * @param multipartFile 图片文件
     */
    private void validPicture(MultipartFile multipartFile) {
        ThrowUtils.throwIf(multipartFile==null,ErrorCode.PARAMS_ERROR,FILE_CAN_NOT_BE_NULL);
        //1.校验文件大小
        long fileSize = multipartFile.getSize();
        //空文件不是合法图片：直接拒掉（0 字节会一路传到对象存储）
        ThrowUtils.throwIf(fileSize==0,ErrorCode.PARAMS_ERROR,FILE_CONTENT_EMPTY);
        //不能超过 2M
        ThrowUtils.throwIf(fileSize>2*ONE_M,ErrorCode.PARAMS_ERROR,FILE_SIZE_ERROR);
        //2. 校验文件后缀
        String fileSuffix = FileUtil.getSuffix(multipartFile.getOriginalFilename());
        ThrowUtils.throwIf(!ALLOW_FILE_SUFFIX.contains(fileSuffix),ErrorCode.PARAMS_ERROR,FILE_TYPE_ERROR);
    }

    /**
     * 删除临时文件
     * @param file 文件
     */
    private void deleteTemFile(File file) {
        if(file==null){
            return;
        }
        //删除临时文件
        boolean deleteResult = file.delete();
        if(!deleteResult){
            log.error("file delete fail,filePath:{}",file.getAbsolutePath());
        }
    }
}

