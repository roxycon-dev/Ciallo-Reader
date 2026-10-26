localhost-test-only.p12 is a PUBLIC TEST FIXTURE, not an application signing key.
Its password is changeit. It contains a self-signed localhost certificate valid
from 2020-01-01 for 7300 days, with SAN localhost and 127.0.0.1 and serverAuth EKU.
Only JVM tests load it; it must never be copied into src/main or production trust stores.

Generated with the JDK keytool:
keytool -genkeypair -alias localhost -keyalg RSA -keysize 2048 -validity 7300
  -startdate "2020/01/01 00:00:00" -dname "CN=localhost,O=Ciallo TLS test fixture"
  -ext "SAN=dns:localhost,ip:127.0.0.1" -ext "EKU=serverAuth"
  -storetype PKCS12 -keystore localhost-test-only.p12
  -storepass changeit -keypass changeit -noprompt
