FROM eclipse-temurin:17

WORKDIR /app

COPY . .

# Compile ONLY your project files (not lib folder)
RUN javac -cp ".:lib/mysql-connector-j-9.7.0.jar" $(find . -path "./lib" -prune -o -name "*.java" -print)

EXPOSE 4567

CMD ["java", "-cp", ".:lib/mysql-connector-j-9.7.0.jar", "VotingApi"]
