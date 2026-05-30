import os
import sys
import xml.etree.ElementTree as ET

path = sys.argv[1]
http = int(os.environ.get("WILDFLY_HTTP_PORT", "32318"))
ajp = int(os.environ.get("WILDFLY_AJP_PORT") or http + 1)
https = int(os.environ.get("WILDFLY_HTTPS_PORT") or http + 2)
mgmt_http = int(os.environ.get("WILDFLY_MANAGEMENT_HTTP_PORT") or http + 3)
mgmt_https = int(os.environ.get("WILDFLY_MANAGEMENT_HTTPS_PORT") or http + 4)
ports = {
    "http": http,
    "ajp": ajp,
    "https": https,
    "management-http": mgmt_http,
    "management-https": mgmt_https,
}
tree = ET.parse(path)
root = tree.getroot()
ns = {"x": root.tag[root.tag.find("{") + 1:root.tag.find("}")]}
ET.register_namespace("", ns["x"])
for interface in root.findall("x:interfaces/x:interface", ns):
    if interface.get("name") == "public":
        for child in list(interface):
            interface.remove(child)
        ET.SubElement(interface, "{%s}any-address" % ns["x"])
for binding in root.findall(".//x:socket-binding", ns):
    name = binding.get("name")
    if name in ports:
        binding.set("port", "${jboss.%s.port:%d}" % (name.replace("-", "."), ports[name]))
tree.write(path, encoding="UTF-8", xml_declaration=True)
