FROM hseeberger/scala-sbt:8u222_1.3.5_2.13.1
WORKDIR /app

# Copy the application source in.
COPY ./ ./

# Build it.
RUN sbt compile

# Set the command to run and other metadata when the container starts.
EXPOSE 9000
CMD sbt run
