FROM hseeberger/scala-sbt:8u312_1.6.2_2.13.8
WORKDIR /app

# Copy the application source in.
COPY ./ ./

# Build it.
RUN sbt docker:publishLocal
