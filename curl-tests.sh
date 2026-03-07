BASE_URL="http://localhost:8080/api/v1"

echo "1. Register HR"
HR_AUTH=$(curl -s -X POST "$BASE_URL/auth/register" \
  -H "Content-Type: application/json" \
  -d '{
    "email":"hr@example.com",
    "password":"Admin123!",
    "role":"HR"
  }')
echo "$HR_AUTH"
HR_TOKEN=$(echo "$HR_AUTH" | sed -n 's/.*"accessToken":"\([^"]*\)".*/\1/p')
HR_REFRESH=$(echo "$HR_AUTH" | sed -n 's/.*"refreshToken":"\([^"]*\)".*/\1/p')
echo -e "\n"

echo "2. Register candidate"
USER_AUTH=$(curl -s -X POST "$BASE_URL/auth/register" \
  -H "Content-Type: application/json" \
  -d '{
    "email":"ivan@example.com",
    "password":"User123!",
    "role":"USER"
  }')
echo "$USER_AUTH"
USER_TOKEN=$(echo "$USER_AUTH" | sed -n 's/.*"accessToken":"\([^"]*\)".*/\1/p')
USER_REFRESH=$(echo "$USER_AUTH" | sed -n 's/.*"refreshToken":"\([^"]*\)".*/\1/p')
echo -e "\n"

echo "3. Create vacancy as HR"
curl -s -X POST "$BASE_URL/vacancies" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $HR_TOKEN" \
  -d '{
    "title":"Java Developer",
    "description":"Разработка backend на Spring Boot"
  }'
echo -e "\n"

echo "4. Create candidate profile"
curl -s -X POST "$BASE_URL/candidates" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $USER_TOKEN" \
  -d '{
    "fullName":"Иван Петров",
    "email":"candidate-profile@example.com",
    "phone":"+79990000000",
    "resumeText":"Java, Spring, PostgreSQL"
  }'
echo -e "\n"

echo "5. Create application"
curl -s -X POST "$BASE_URL/applications" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $USER_TOKEN" \
  -d '{
    "vacancyId":1,
    "candidateId":1,
    "coverLetter":"Хочу работать у вас"
  }'
echo -e "\n"

echo "6. Validate application as HR"
curl -s -X POST "$BASE_URL/applications/1/validate" \
  -H "Authorization: Bearer $HR_TOKEN"
echo -e "\n"

echo "7. Invite candidate as HR"
curl -s -X POST "$BASE_URL/applications/1/invite" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $HR_TOKEN" \
  -d '{
    "comment":"Приглашаем на техническое интервью"
  }'
echo -e "\n"

echo "8. Candidate accepts invitation"
curl -s -X POST "$BASE_URL/applications/1/accept" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $USER_TOKEN" \
  -d '{
    "comment":"Подтверждаю участие"
  }'
echo -e "\n"

echo "9. Refresh HR token"
curl -s -X POST "$BASE_URL/auth/refresh" \
  -H "Content-Type: application/json" \
  -d "{\"refreshToken\":\"$HR_REFRESH\"}"
echo -e "\n"

echo "10. Logout candidate"
curl -s -X POST "$BASE_URL/auth/logout" \
  -H "Content-Type: application/json" \
  -d "{\"refreshToken\":\"$USER_REFRESH\"}"
echo -e "\n"
