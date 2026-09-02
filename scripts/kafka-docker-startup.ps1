# Остановка и удаление запущенных компьютеров
docker rm -f kafka kafka-ui
# Запуск kafka
docker run -d `
    --name kafka --network clientbus-net -p 9092:9092 `
    -e "KAFKA_NODE_ID=1" `
    -e "KAFKA_PROCESS_ROLES=broker,controller" `
    -e "KAFKA_CONTROLLER_QUORUM_VOTERS=1@kafka:29093" `
    -e "KAFKA_LISTENERS=PLAINTEXT://0.0.0.0:29092,EXTERNAL://0.0.0.0:9092,CONTROLLER://0.0.0.0:29093" `
    -e "KAFKA_ADVERTISED_LISTENERS=PLAINTEXT://kafka:29092,EXTERNAL://localhost:9092" `
    -e "KAFKA_LISTENER_SECURITY_PROTOCOL_MAP=PLAINTEXT:PLAINTEXT,EXTERNAL:PLAINTEXT,CONTROLLER:PLAINTEXT" `
    -e "KAFKA_INTER_BROKER_LISTENER_NAME=PLAINTEXT" `
    -e "KAFKA_CONTROLLER_LISTENER_NAMES=CONTROLLER" `
    -e "KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR=1" `
    -e "KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR=1" `
    -e "KAFKA_TRANSACTION_STATE_LOG_MIN_ISR=1" `
    apache/kafka:latest

# Запуск kafka-ui
docker run -d `
  --name kafka-ui `
  --network clientbus-net `
  -p 18080:8080 `
  -e "DYNAMIC_CONFIG_ENABLED=true" `
  -e "KAFKA_CLUSTERS_0_NAME=local" `
  -e "KAFKA_CLUSTERS_0_BOOTSTRAPSERVERS=kafka:29092" `
  ghcr.io/kafbat/kafka-ui

