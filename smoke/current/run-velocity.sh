#!/bin/sh
cp /proxy/config/velocity.toml /proxy/velocity.toml
exec java -Xms256m -Xmx512m -jar /proxy/runtime/velocity-4.1.0-SNAPSHOT-16.jar