package com.csust.eyediagnosis.controlcenter.service;

import com.csust.eyediagnosis.controlcenter.model.entity.ConfigActivePointer;
import com.csust.eyediagnosis.controlcenter.model.entity.ConfigRelease;
import com.csust.eyediagnosis.controlcenter.model.vo.ActiveConfigVO;
import com.csust.eyediagnosis.controlcenter.model.vo.PageVO;
import com.csust.eyediagnosis.controlcenter.model.vo.RegistryVO;
import com.csust.eyediagnosis.controlcenter.model.vo.ReleaseVO;
import com.csust.eyediagnosis.controlcenter.registry.ConfigKey;
import com.csust.eyediagnosis.controlcenter.registry.ConfigRegistry;
import com.csust.eyediagnosis.exception.BusinessException;
import com.csust.eyediagnosis.exception.ErrorCode;
import com.csust.eyediagnosis.mapper.ConfigActivePointerMapper;
import com.csust.eyediagnosis.mapper.ConfigReleaseMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 配置读取：注册表与当前生效配置。
 *
 * <p>未发布过任何版本时返回代码默认值，而不是空对象（AC-01-01）——
 * 空库启动的站点必须和发布过的站点一样完整。
 */
@Slf4j
@Service
public class ControlCenterReadService {

    private final ConfigRegistry registry;
    private final ConfigPayloadCodec codec;
    private final ConfigActivePointerMapper pointerMapper;
    private final ConfigReleaseMapper releaseMapper;

    public ControlCenterReadService(ConfigRegistry registry,
                                    ConfigPayloadCodec codec,
                                    ConfigActivePointerMapper pointerMapper,
                                    ConfigReleaseMapper releaseMapper) {
        this.registry = registry;
        this.codec = codec;
        this.pointerMapper = pointerMapper;
        this.releaseMapper = releaseMapper;
    }

    public RegistryVO registry() {
        List<RegistryVO.KeyVO> keys = registry.definitions().stream()
                .map(this::toKeyVO)
                .toList();
        return new RegistryVO(ConfigRegistry.SCHEMA_VERSION, keys);
    }

    /** 当前生效配置；指针为空时全部回落到代码默认值。 */
    public ActiveConfigVO active() {
        ConfigActivePointer pointer = pointerMapper.selectById(ConfigActivePointer.SINGLETON_ID);
        Long releaseId = pointer == null ? null : pointer.getReleaseId();
        if (releaseId == null) {
            return new ActiveConfigVO(null, null, ConfigRegistry.SCHEMA_VERSION, null,
                    "DEFAULT", codec.overlay(registry.defaults(), Map.of()), null);
        }

        ConfigRelease release = releaseMapper.selectById(releaseId);
        if (release == null) {
            // 指针指向不存在的 release：数据被外部改坏。宁可显式失败，
            // 也不要把「读不到的配置」静默替换成默认值——那会让线上的品牌
            // 在无人知晓的情况下被换掉。
            log.error("配置中心活跃指针指向不存在的 release, releaseId={}", releaseId);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "配置指针损坏，请联系管理员");
        }

        Map<String, Object> values = codec.overlay(registry.defaults(), codec.parse(release.getPayload()));
        return new ActiveConfigVO(release.getId(), release.getReleaseVersion(),
                release.getSchemaVersion(), release.getDigest(), "RELEASE", values,
                release.getCreatedTime());
    }

    /** 发布历史（按 releaseVersion 倒序）；只读，不区分当前活跃版本。 */
    public PageVO<ReleaseVO> releases(int page, int pageSize) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(pageSize, 1), 100);
        long total = releaseMapper.countAll();
        List<ReleaseVO> records = releaseMapper.page(safeSize, (safePage - 1) * safeSize)
                .stream()
                .map(this::toReleaseVO)
                .toList();
        return new PageVO<>(records, total, safePage, safeSize);
    }

    /** 单个发布快照；不存在即 40400（历史记录是可追溯的，不能返回空对象）。 */
    public ReleaseVO release(long id) {
        ConfigRelease release = releaseMapper.selectById(id);
        if (release == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "发布版本不存在");
        }
        return toReleaseVO(release);
    }

    private ReleaseVO toReleaseVO(ConfigRelease release) {
        Map<String, Object> values = codec.overlay(registry.defaults(), codec.parse(release.getPayload()));
        return new ReleaseVO(release.getId(), release.getReleaseVersion(), release.getSchemaVersion(),
                release.getDigest(), release.getRollbackFromReleaseId(), release.getCreatedBy(),
                release.getCreatedTime(), values);
    }

    private RegistryVO.KeyVO toKeyVO(ConfigKey definition) {        return new RegistryVO.KeyVO(definition.key(), definition.type().name(),
                definition.defaultValue(), definition.minLength(), definition.maxLength());
    }
}
