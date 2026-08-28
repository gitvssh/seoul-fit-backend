#!/bin/sh
set -eu

[ "$#" -ge 4 ]
[ "$1" = '-Xmx512m' ]
[ "$2" = '-Xms256m' ]
[ "$3" = '-jar' ]
[ "$4" = '/app/app.jar' ]

printf '%s\n' '{"log_schema":"spring_boot_otel_json_v1","event_name":"runtime.contract.after-marker"}'
