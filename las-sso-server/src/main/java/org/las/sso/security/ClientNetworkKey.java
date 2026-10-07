package org.las.sso.security;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.HexFormat;
import org.springframework.stereotype.Component;

@Component
public class ClientNetworkKey {

    public String fromRemoteAddress(String remoteAddress) {
        if (remoteAddress == null || remoteAddress.isBlank()) {
            return "unknown";
        }
        try {
            byte[] address = InetAddress.getByName(remoteAddress).getAddress();
            if (address.length == 4) {
                address[3] = 0;
                return "v4/24:" + HexFormat.of().formatHex(address);
            }
            for (int index = 7; index < address.length; index++) {
                address[index] = 0;
            }
            address[7] &= (byte) 0x00;
            return "v6/56:" + HexFormat.of().formatHex(address);
        } catch (UnknownHostException exception) {
            return "invalid:" + remoteAddress;
        }
    }
}
