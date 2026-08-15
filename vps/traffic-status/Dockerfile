FROM python:3.13-alpine

WORKDIR /app
COPY app.py /app/app.py

RUN adduser -D -u 1000 traffic
USER 1000:1000

EXPOSE 8080
CMD ["python", "-u", "/app/app.py"]
