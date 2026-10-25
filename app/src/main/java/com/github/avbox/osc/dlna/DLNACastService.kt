package com.github.avbox.osc.dlna

import org.fourthline.cling.UpnpServiceConfiguration
import org.fourthline.cling.android.AndroidUpnpServiceImpl
import org.fourthline.cling.model.types.ServiceType
import org.fourthline.cling.model.types.UDAServiceType

class DLNACastService : AndroidUpnpServiceImpl() {
    override fun createConfiguration(): UpnpServiceConfiguration {
        return object : DLNAServiceConfiguration() {
            override fun getExclusiveServiceTypes(): Array<ServiceType> {
                return arrayOf(UDAServiceType("AVTransport", 1))
            }
        }
    }
}
