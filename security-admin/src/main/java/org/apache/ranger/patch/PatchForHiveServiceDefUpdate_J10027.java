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

package org.apache.ranger.patch;

import org.apache.commons.lang.StringUtils;
import org.apache.ranger.biz.RangerBizUtil;
import org.apache.ranger.biz.ServiceDBStore;
import org.apache.ranger.common.JSONUtil;
import org.apache.ranger.common.RangerValidatorFactory;
import org.apache.ranger.common.StringUtil;
import org.apache.ranger.db.RangerDaoManager;
import org.apache.ranger.entity.XXServiceDef;
import org.apache.ranger.plugin.model.RangerServiceDef;
import org.apache.ranger.plugin.model.validation.RangerServiceDefValidator;
import org.apache.ranger.plugin.model.validation.RangerValidator;
import org.apache.ranger.plugin.store.EmbeddedServiceDefsUtil;
import org.apache.ranger.service.RangerPolicyService;
import org.apache.ranger.service.XPermMapService;
import org.apache.ranger.service.XPolicyService;
import org.apache.ranger.util.CLIUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class PatchForHiveServiceDefUpdate_J10027 extends BaseLoader {
    private static final Logger logger = LoggerFactory.getLogger(PatchForHiveServiceDefUpdate_J10027.class);

    public static final String SERVICEDBSTORE_SERVICEDEFBYNAME_HIVE_NAME = "hive";
    public static final String REFRESH_ACCESS_TYPE_NAME                  = "refresh";

    @Autowired
    RangerDaoManager daoMgr;

    @Autowired
    ServiceDBStore svcDBStore;

    @Autowired
    JSONUtil jsonUtil;

    @Autowired
    RangerPolicyService policyService;

    @Autowired
    StringUtil stringUtil;

    @Autowired
    XPolicyService xPolService;

    @Autowired
    XPermMapService xPermMapService;

    @Autowired
    RangerBizUtil bizUtil;

    @Autowired
    RangerValidatorFactory validatorFactory;

    @Autowired
    ServiceDBStore svcStore;

    public static void main(String[] args) {
        logger.info("main()");

        try {
            PatchForHiveServiceDefUpdate_J10027 loader = (PatchForHiveServiceDefUpdate_J10027) CLIUtil.getBean(PatchForHiveServiceDefUpdate_J10027.class);

            loader.init();

            while (loader.isMoreToProcess()) {
                loader.load();
            }

            logger.info("Load complete. Exiting.");

            System.exit(0);
        } catch (Exception e) {
            logger.error("Error loading", e);

            System.exit(1);
        }
    }

    @Override
    public void init() throws Exception {
        // Do Nothing
    }

    @Override
    public void printStats() {
        logger.info("PatchForHiveServiceDefUpdate data ");
    }

    @Override
    public void execLoad() {
        logger.info("==> PatchForHiveServiceDefUpdate.execLoad()");
        try {
            if (!updateHiveServiceDef()) {
                logger.error("Failed to apply the patch.");

                System.exit(1);
            }
        } catch (Exception e) {
            logger.error("Error while updateHiveServiceDef()data.", e);

            System.exit(1);
        }

        logger.info("<== PatchForHiveServiceDefUpdate.execLoad()");
    }

    protected Map<String, String> jsonStringToMap(String jsonStr) {
        Map<String, String> ret = null;

        if (!StringUtils.isEmpty(jsonStr)) {
            try {
                ret = jsonUtil.jsonToMap(jsonStr);
            } catch (Exception ex) {
                // fallback to earlier format: "name1=value1;name2=value2"
                for (String optionString : jsonStr.split(";")) {
                    if (StringUtils.isEmpty(optionString)) {
                        continue;
                    }

                    String[] nvArr = optionString.split("=");
                    String   name  = nvArr.length > 0 ? nvArr[0].trim() : null;
                    String   value = nvArr.length > 1 ? nvArr[1].trim() : null;

                    if (StringUtils.isEmpty(name)) {
                        continue;
                    }

                    if (ret == null) {
                        ret = new HashMap<>();
                    }

                    ret.put(name, value);
                }
            }
        }
        return ret;
    }

    private boolean updateHiveServiceDef() throws Exception {
        RangerServiceDef embeddedHiveServiceDef = EmbeddedServiceDefsUtil.instance().getEmbeddedServiceDef(SERVICEDBSTORE_SERVICEDEFBYNAME_HIVE_NAME);

        if (embeddedHiveServiceDef != null) {
            XXServiceDef        xXServiceDefObj = daoMgr.getXXServiceDef().findByName(SERVICEDBSTORE_SERVICEDEFBYNAME_HIVE_NAME);
            Map<String, String> serviceDefOptionsPreUpdate;
            String              jsonPreUpdate;

            if (xXServiceDefObj != null) {
                jsonPreUpdate              = xXServiceDefObj.getDefOptions();
                serviceDefOptionsPreUpdate = jsonStringToMap(jsonPreUpdate);
            } else {
                logger.error("Hive service-definition does not exist in the Ranger DAO. No patching is needed!!");

                return true;
            }

            RangerServiceDef dbHiveServiceDef = svcDBStore.getServiceDefByName(SERVICEDBSTORE_SERVICEDEFBYNAME_HIVE_NAME);

            if (dbHiveServiceDef != null) {
                List<RangerServiceDef.RangerAccessTypeDef> embeddedHiveAccessTypes = embeddedHiveServiceDef.getAccessTypes();

                if (embeddedHiveAccessTypes != null) {
                    if (checkNewHiveAccessTypesPresent(embeddedHiveAccessTypes)) {
                        if (!embeddedHiveAccessTypes.toString().equalsIgnoreCase(dbHiveServiceDef.getAccessTypes().toString())) {
                            dbHiveServiceDef.setAccessTypes(embeddedHiveAccessTypes);
                        }
                    }
                }
            } else {
                logger.error("Hive service-definition does not exist in the db store.");

                return false;
            }

            RangerServiceDefValidator validator = validatorFactory.getServiceDefValidator(svcStore);

            validator.validate(dbHiveServiceDef, RangerValidator.Action.UPDATE);

            RangerServiceDef ret = svcStore.updateServiceDef(dbHiveServiceDef);

            if (ret == null) {
                throw new RuntimeException("Error while updating " + SERVICEDBSTORE_SERVICEDEFBYNAME_HIVE_NAME + " service-def");
            }

            xXServiceDefObj = daoMgr.getXXServiceDef().findByName(SERVICEDBSTORE_SERVICEDEFBYNAME_HIVE_NAME);

            if (xXServiceDefObj != null) {
                String              jsonStrPostUpdate           = xXServiceDefObj.getDefOptions();
                Map<String, String> serviceDefOptionsPostUpdate = jsonStringToMap(jsonStrPostUpdate);

                if (serviceDefOptionsPostUpdate != null && serviceDefOptionsPostUpdate.containsKey(RangerServiceDef.OPTION_ENABLE_DENY_AND_EXCEPTIONS_IN_POLICIES)) {
                    if (serviceDefOptionsPreUpdate == null || !serviceDefOptionsPreUpdate.containsKey(RangerServiceDef.OPTION_ENABLE_DENY_AND_EXCEPTIONS_IN_POLICIES)) {
                        String preUpdateValue = serviceDefOptionsPreUpdate == null ? null : serviceDefOptionsPreUpdate.get(RangerServiceDef.OPTION_ENABLE_DENY_AND_EXCEPTIONS_IN_POLICIES);

                        if (preUpdateValue == null) {
                            serviceDefOptionsPostUpdate.remove(RangerServiceDef.OPTION_ENABLE_DENY_AND_EXCEPTIONS_IN_POLICIES);
                        } else {
                            serviceDefOptionsPostUpdate.put(RangerServiceDef.OPTION_ENABLE_DENY_AND_EXCEPTIONS_IN_POLICIES, preUpdateValue);
                        }

                        xXServiceDefObj.setDefOptions(mapToJsonString(serviceDefOptionsPostUpdate));

                        daoMgr.getXXServiceDef().update(xXServiceDefObj);
                    }
                }
            } else {
                logger.error("Hive service-definition does not exist in the Ranger DAO.");

                return false;
            }
        } else {
            logger.error("The embedded Hive service-definition does not exist.");

            return false;
        }

        return true;
    }

    private static boolean checkNewHiveAccessTypesPresent(List<RangerServiceDef.RangerAccessTypeDef> accessTypeDefs) {
        boolean ret = false;

        for (RangerServiceDef.RangerAccessTypeDef accessTypeDef : accessTypeDefs) {
            if (REFRESH_ACCESS_TYPE_NAME.equals(accessTypeDef.getName())) {
                ret = true;
                break;
            }
        }

        return ret;
    }

    private String mapToJsonString(Map<String, String> map) {
        String ret = null;

        if (map != null) {
            try {
                ret = jsonUtil.readMapToString(map);
            } catch (Exception ex) {
                logger.warn("mapToJsonString() failed to convert map: {}", map, ex);
            }
        }

        return ret;
    }
}
