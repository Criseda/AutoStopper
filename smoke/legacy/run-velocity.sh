#!/bin/sh
cp /proxy/config/velocity.toml /proxy/velocity.toml
exec java -Xms256m -Xmx512m -jar /proxy/runtime/velocity-3.5.1-615.jar