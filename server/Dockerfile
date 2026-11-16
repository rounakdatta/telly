# The telly server: standard library only, so the image is the stock interpreter plus the package.
# Personal data never goes in here: the catalogue and telly.db live on the volume mounted at /data.
FROM python:3.14-alpine
RUN adduser -D -u 1000 telly && mkdir /data && chown telly /data
WORKDIR /app
COPY telly /app/telly
USER 1000
ENV PYTHONUNBUFFERED=1 PYTHONDONTWRITEBYTECODE=1
EXPOSE 8080
ENTRYPOINT ["python", "-m", "telly", "--data", "/data", "--port", "8080"]
