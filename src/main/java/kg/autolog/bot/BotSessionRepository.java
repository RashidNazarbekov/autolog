package kg.autolog.bot;

import org.springframework.data.jpa.repository.JpaRepository;

interface BotSessionRepository extends JpaRepository<BotSession, Long> {
}
