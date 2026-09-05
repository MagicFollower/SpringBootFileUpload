package com.example.fileupload.strategy;

import com.example.fileupload.enums.UploadType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 策略工厂：自动注册所有 UploadStrategy，根据 UploadType 路由
 */
@Component
public class UploadStrategyFactory {

    private static final Logger log = LoggerFactory.getLogger(UploadStrategyFactory.class);

    private final Map<UploadType, UploadStrategy> strategyMap = new ConcurrentHashMap<>();
    private List<UploadStrategy> allStrategies;

    public UploadStrategyFactory(List<UploadStrategy> allStrategies) {
        this.allStrategies = allStrategies;
    }

    @PostConstruct
    private void buildStrategyMap() {
        for (UploadStrategy s : allStrategies) {
            UploadType type = s.getUploadType();
            if (strategyMap.containsKey(type)) {
                throw new IllegalStateException("Duplicate UploadStrategy for type: " + type.getCode());
            }
            strategyMap.put(type, s);
        }
        log.info("UploadStrategyFactory initialized with {} strategies: {}",
                strategyMap.size(), strategyMap.keySet());
    }

    /**
     * 根据上传类型获取对应的策略
     *
     * @param uploadType 上传类型枚举
     * @return 具体策略实例
     * @throws IllegalArgumentException 当找不到对应策略时抛出
     */
    public UploadStrategy getStrategy(UploadType uploadType) {
        UploadStrategy strategy = strategyMap.get(uploadType);
        if (strategy == null) {
            throw new IllegalArgumentException(
                    "未找到上传类型 [" + uploadType.getCode() + "] (" + uploadType.getDesc() + ") 对应的策略实现");
        }
        return strategy;
    }
}
