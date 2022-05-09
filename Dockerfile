FROM schlaubiboy/kotlin:1.6.21-jdk16-slim

RUN apk add --no-cache github-cli

COPY update-schema.main.kts update-schema.main.kts

#ENTRYPOINT ["kotlin", "update-schema.main.kts"]

CMD ["sh"]
