/*
 *
 *  * Copyright 2000-2014 JetBrains s.r.o.
 *  *
 *  * Licensed under the Apache License, Version 2.0 (the "License");
 *  * you may not use this file except in compliance with the License.
 *  * You may obtain a copy of the License at
 *  *
 *  * http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  * Unless required by applicable law or agreed to in writing, software
 *  * distributed under the License is distributed on an "AS IS" BASIS,
 *  * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  * See the License for the specific language governing permissions and
 *  * limitations under the License.
 *
 */

package jetbrains.buildServer.clouds.vmware.connector;

import com.intellij.openapi.diagnostic.Logger;
import com.vmware.vim25.OptionValue;
import com.vmware.vim25.VirtualMachineConfigInfo;
import com.vmware.vim25.VirtualMachinePowerState;
import com.vmware.vim25.VirtualMachineRuntimeInfo;
import com.vmware.vim25.mo.Datacenter;
import com.vmware.vim25.mo.Task;
import com.vmware.vim25.mo.VirtualMachine;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import jetbrains.buildServer.clouds.InstanceStatus;
import jetbrains.buildServer.clouds.base.connector.AbstractInstance;
import jetbrains.buildServer.clouds.base.connector.AsyncCloudTask;
import jetbrains.buildServer.util.StringUtil;
import jetbrains.buildServer.util.impl.Lazy;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * @author Sergey.Pak
 *         Date: 7/25/2014
 *         Time: 6:45 PM
 */
public class VmwareInstance extends AbstractInstance implements VmwareManagedEntity {
  private static final Logger LOG = Logger.getInstance(VmwareInstance.class.getName());

  @NotNull private final VirtualMachine myVm;
  private final String myId;
  private final Lazy<String> myDatacenterIdLazy;
  private final Map<String, String> myProperties;
  private final String myName;

  public VmwareInstance(@NotNull final VirtualMachine vm) {
    myVm = vm;
    VirtualMachineConfigInfo configInfo = vm.getConfig();
    if (configInfo == null) {
      myName = vm.getName();
    } else {
      myName = configInfo.getName();
    }

    myProperties = configInfo == null ? null : extractProperties(configInfo);
    myId = vm.getMOR().getVal();
    myDatacenterIdLazy = new Lazy<String>() {
      @Nullable
      @Override
      protected String createValue() {
        return calculateDatacenterId();
      }
    };
  }


  @Nullable
  private static Map<String, String> extractProperties(@NotNull final VirtualMachineConfigInfo configInfo) {
    try {
      final OptionValue[] extraConfig = configInfo.getExtraConfig();
      Map<String, String> retval = new HashMap<String, String>();
      for (OptionValue optionValue : extraConfig) {
        retval.put(optionValue.getKey(), String.valueOf(optionValue.getValue()));
      }
      return retval;
    } catch (Exception ex){
      LOG.info("Unable to retrieve instance properties for " + configInfo.getName() + ": " + ex.toString());
      return null;
    }
  }

  public boolean isPoweredOn() {
    final VirtualMachineRuntimeInfo runtime = myVm.getRuntime();
    if (runtime == null) {
      return false;
    }
    return runtime.getPowerState() == VirtualMachinePowerState.poweredOn;
  }

  @NotNull
  @Override
  public String getName() {
    return myName;
  }

  @Override
  public boolean isInitialized() {
    return myProperties != null;
  }

  @Nullable
  public String getProperty(@NotNull final String propertyName) {
    return myProperties == null ? null : myProperties.get(propertyName);
  }

  @Nullable
  public Date getStartDate() {
    final VirtualMachineRuntimeInfo runtime = myVm.getRuntime();
    if (runtime == null || runtime.getPowerState() != VirtualMachinePowerState.poweredOn) {
      return null;
    }
    final Calendar bootTime = runtime.getBootTime();
    return bootTime == null ? null : bootTime.getTime();
  }

  @Nullable
  public String getIpAddress() {
    return myVm.getGuest() == null ? null : myVm.getGuest().getIpAddress();
  }

  @Override
  public InstanceStatus getInstanceStatus() {
    if (myVm.getRuntime() == null || myVm.getRuntime().getPowerState() == VirtualMachinePowerState.poweredOff) {
      return InstanceStatus.STOPPED;
    }
    if (myVm.getRuntime().getPowerState() == VirtualMachinePowerState.poweredOn) {
      return InstanceStatus.RUNNING;
    }
    return InstanceStatus.UNKNOWN;
  }

  public boolean isReadonly() {
    final VirtualMachineConfigInfo config = myVm.getConfig();
    if (config == null) {
      return true;
    }

    return config.isTemplate();
  }

  @Nullable
  public String getChangeVersion() {
    final VirtualMachineConfigInfo config = myVm.getConfig();
    return config == null ? null : config.getChangeVersion();
  }

  public AsyncCloudTask deleteInstance(){
    return new VmwareTaskWrapper(new Callable<Task>() {
      public Task call() throws Exception {
        return myVm.destroy_Task();
      }
    }, "Delete instance " + getName());
  }

  @Nullable
  public String getSnapshotName(){
    return StringUtil.nullIfEmpty(getProperty(VMWareApiConnector.TEAMCITY_VMWARE_IMAGE_SNAPSHOT));
  }

  @NotNull
  public String getId() {
    return myId;
  }

  @Nullable
  public String getDatacenterId() {
    return myDatacenterIdLazy.getValue();
  }

  private String calculateDatacenterId() {
    final Datacenter datacenter = VmwareUtils.getDatacenter(myVm);
    if (datacenter != null) {
      return datacenter.getMOR().getVal();
    } else {
      return null;
    }
  }

  public String getImageName(){
    final String nickname = getProperty(VMWareApiConnector.TEAMCITY_VMWARE_IMAGE_SOURCE_ID);

    // for backward compatibility
    return nickname == null ? getProperty(VMWareApiConnector.TEAMCITY_VMWARE_IMAGE_SOURCE_VM_NAME) : nickname;
  }

  @Nullable
  public String getServerUUID(){
    return getProperty(VMWareApiConnector.TEAMCITY_VMWARE_SERVER_UUID);
  }

  public String getProfileId(){
    return getProperty(VMWareApiConnector.TEAMCITY_VMWARE_PROFILE_ID);
  }

  public boolean isClone(){
    return "true".equals(getProperty(VMWareApiConnector.TEAMCITY_VMWARE_CLONED_INSTANCE));
  }
}
