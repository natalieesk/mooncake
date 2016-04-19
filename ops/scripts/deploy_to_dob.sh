#!/bin/bash

ssh $REMOTE_USER@$SERVER_IP "mkdir -p /var/mooncake/config"
scp mooncake.env $REMOTE_USER@$SERVER_IP:/var/mooncake/config/mooncake.env
scp activity-sources.yml $REMOTE_USER@$SERVER_IP:/var/mooncake/config/activity-sources.yml
ssh $REMOTE_USER@$SERVER_IP <<EOF
  echo $REMOTE_PASSWORD | sudo -S docker stop mooncake || echo 'Failed to stop mooncake container'
  sudo docker rm mooncake || echo 'Failed to remove mooncake container'
  sudo docker run -d -v /var/mooncake/config:/var/mooncake/config \
                  --env-file=/var/mooncake/config/mooncake.env \
                  -p 127.0.0.1:5000:3000 \
                  --link mongo:mongo \
                  --name mooncake \
                  --restart=on-failure \
                  dcent/mooncake
EOF
