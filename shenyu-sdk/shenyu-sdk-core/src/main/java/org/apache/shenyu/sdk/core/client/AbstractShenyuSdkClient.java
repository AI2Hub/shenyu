/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.shenyu.sdk.core.client;

import org.apache.commons.lang3.StringUtils;
import org.apache.shenyu.common.config.ShenyuConfig;
import org.apache.shenyu.common.utils.Singleton;
import org.apache.shenyu.register.common.dto.InstanceRegisterDTO;
import org.apache.shenyu.register.instance.api.ShenyuInstanceRegisterRepository;
import org.apache.shenyu.register.instance.core.ShenyuInstanceRegisterRepositoryFactory;
import org.apache.shenyu.sdk.core.ShenyuRequest;
import org.apache.shenyu.sdk.core.ShenyuResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static java.util.concurrent.TimeUnit.SECONDS;

public abstract class AbstractShenyuSdkClient implements ShenyuSdkClient {

    private static final Logger log = LoggerFactory.getLogger(AbstractShenyuSdkClient.class);

    private static final String URL_REWRITE_REGEX = ":\\/\\/[a-z\\d\\.|:]+\\/";

    private final Retryer retryer;

    private final ShenyuInstanceRegisterRepository registerRepository;

    private final ShenyuConfig.RegisterConfig sdkConfig;

    public AbstractShenyuSdkClient() {
        this.sdkConfig = Singleton.INST.get(ShenyuConfig.class).getSdk();
        this.registerRepository = ShenyuInstanceRegisterRepositoryFactory.newInstance(sdkConfig.getRegisterType());

        Boolean retryEnable = Optional.ofNullable(sdkConfig.getProps().get("retry.enable")).map(e -> (boolean) e).orElse(false);
        Long period = Optional.ofNullable(sdkConfig.getProps().get("retry.period")).map(l -> (Long) l).orElse(100L);
        long maxPeriod = Optional.ofNullable(sdkConfig.getProps().get("retry.maxPeriod")).map(l -> (Long) l).orElse(SECONDS.toMillis(1));
        int maxAttempts = Optional.ofNullable(sdkConfig.getProps().get("retry.maxAttempts")).map(l -> (int) l).orElse(5);
        this.retryer = retryEnable ? new Retryer.DefaultRetry(period, maxPeriod, maxAttempts) : Retryer.NEVER_RETRY;

    }

    @Override
    public ShenyuResponse execute(final ShenyuRequest request) throws IOException {
        // do request.
        Retryer retryer = this.retryer.instance();
        while (true) {
            try {
                return doRequest(rewriteShenyuRequest(request));
            } catch (RetryableException e) {
                retryer.continueOrPropagate(e);
                log.warn("request fail, retry. contextId: {}", request.getContextId());
            }
        }
    }

    private ShenyuRequest rewriteShenyuRequest(final ShenyuRequest request) {
        return ShenyuRequest.create(getRewriteUrl(request), request);
    }

    /**
     * TODO wait append load balance logic.
     * get rewrite url by request.
     *
     * @param request the request.
     * @return {@linkplain String}
     */
    private String getRewriteUrl(final ShenyuRequest request) {
        String url;

        if (StringUtils.isEmpty(sdkConfig.getRegisterType())) {
            throw new IllegalArgumentException("configure registerType is required.");
        }

        if ("local".equals(sdkConfig.getRegisterType())) {
            List<InstanceRegisterDTO> instanceRegister = registerRepository.getInstanceRegisterList();
            InstanceRegisterDTO instanceRegisterDTO = instanceRegister.stream().findFirst().orElse(new InstanceRegisterDTO());
            url = request.getUrl().replaceAll(
                    URL_REWRITE_REGEX,
                    String.format("://%s:%s/%s",
                    instanceRegisterDTO.getHost(),
                    instanceRegisterDTO.getPort(),
                    instanceRegisterDTO.getAppName()
            ));
        } else {
            List<String> serverList = Arrays.asList(sdkConfig.getServerLists().split(","));
            if (serverList.isEmpty()) {
                throw new IllegalArgumentException("illegal param, serverLists configuration required if registerType equals local.");
            }
            String serverAddress = serverList.stream().findFirst().orElse("");
            if (!serverAddress.startsWith("http://") && !serverAddress.startsWith("https://")) {
                serverAddress = "http://" + serverAddress;
            }
            url = request.getUrl().replaceAll(URL_REWRITE_REGEX, serverAddress);
        }

        return url;
    }

    protected abstract ShenyuResponse doRequest(ShenyuRequest request) throws IOException;

}