package io.github.furlanettoeduardo.radar.ingestion.persistence;

import io.github.furlanettoeduardo.radar.domain.common.BrazilianState;
import io.github.furlanettoeduardo.radar.domain.common.MonetaryValue;
import io.github.furlanettoeduardo.radar.domain.port.ProcurementRepository;
import io.github.furlanettoeduardo.radar.domain.procurement.Modality;
import io.github.furlanettoeduardo.radar.domain.procurement.PncpControlNumber;
import io.github.furlanettoeduardo.radar.domain.procurement.Procurement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The PostgreSQL side of {@link ProcurementRepository}.
 *
 * <p>Both writes are conditional in SQL, not in Java. {@code ON CONFLICT DO NOTHING} and an {@code
 * UPDATE} whose {@code WHERE} carries the expected hash are decided by the database under its own
 * row locks, so two consumers racing on the same notice get one winner however the scheduler
 * interleaves them. Reading first and then writing would be the same code with a hole in the
 * middle.
 *
 * <p>Neither write knows the ingestion rule. They report whether they won; {@code
 * ProcurementIngestion} decides what that means. Putting the "newer wins" comparison into the SQL
 * would be a second copy of a rule that already exists in the domain, free to drift from it.
 *
 * <p>Registered by {@link PersistenceConfiguration} rather than annotated {@code @Repository}. That
 * annotation asks Spring to wrap the bean in a CGLIB proxy for persistence exception translation,
 * which cannot subclass a final class, and the translation it offers is already done by {@link
 * JdbcClient} itself. An explicit bean keeps the class final and keeps a proxy off a path that runs
 * once per message.
 *
 * <p>Timestamps cross as {@link OffsetDateTime} at UTC because the PostgreSQL driver has no mapping
 * for {@link Instant}. {@code TIMESTAMPTZ} keeps microseconds, which is finer than anything PNCP
 * publishes, and {@code ProcurementRepositoryContract} pins the round trip rather than trusting it.
 */
public final class JdbcProcurementRepository implements ProcurementRepository {

  private static final String COLUMNS =
      """
      control_number, object_description, state, estimated_value, modality_code, modality_name,
      published_at, proposal_opens_at, proposal_closes_at, source_payload_hash, source_updated_at
      """;

  private final JdbcClient jdbc;

  public JdbcProcurementRepository(JdbcClient jdbc) {
    this.jdbc = Objects.requireNonNull(jdbc, "a JDBC repository needs a client");
  }

  @Override
  public Optional<Procurement> findByControlNumber(PncpControlNumber controlNumber) {
    return jdbc.sql("SELECT " + COLUMNS + " FROM procurement WHERE control_number = :controlNumber")
        .param("controlNumber", controlNumber.value())
        .query(JdbcProcurementRepository::toProcurement)
        .optional();
  }

  @Override
  public boolean insertIfAbsent(Procurement procurement) {
    // ON CONFLICT DO NOTHING rather than a SELECT then an INSERT: the database decides, so there is
    // no window between the two for another consumer to slip into.
    int rows =
        bind(
                jdbc.sql(
                    """
                    INSERT INTO procurement (
                      control_number, object_description, state, estimated_value, modality_code,
                      modality_name, published_at, proposal_opens_at, proposal_closes_at,
                      source_payload_hash, source_updated_at
                    ) VALUES (
                      :controlNumber, :objectDescription, :state, :estimatedValue, :modalityCode,
                      :modalityName, :publishedAt, :proposalOpensAt, :proposalClosesAt,
                      :sourcePayloadHash, :sourceUpdatedAt
                    )
                    ON CONFLICT (control_number) DO NOTHING
                    """),
                procurement)
            .update();
    return rows == 1;
  }

  @Override
  public boolean replaceIfUnchanged(Procurement procurement, String expectedSourcePayloadHash) {
    // The expected hash in the WHERE clause is the whole compare-and-set. No row matches when
    // somebody else has already written, and the update reports zero rows rather than overwriting.
    int rows =
        bind(
                jdbc.sql(
                    """
                    UPDATE procurement SET
                      object_description  = :objectDescription,
                      state               = :state,
                      estimated_value     = :estimatedValue,
                      modality_code       = :modalityCode,
                      modality_name       = :modalityName,
                      published_at        = :publishedAt,
                      proposal_opens_at   = :proposalOpensAt,
                      proposal_closes_at  = :proposalClosesAt,
                      source_payload_hash = :sourcePayloadHash,
                      source_updated_at   = :sourceUpdatedAt
                    WHERE control_number = :controlNumber
                      AND source_payload_hash = :expectedSourcePayloadHash
                    """),
                procurement)
            .param("expectedSourcePayloadHash", expectedSourcePayloadHash)
            .update();
    return rows == 1;
  }

  private static JdbcClient.StatementSpec bind(
      JdbcClient.StatementSpec statement, Procurement procurement) {
    return statement
        .param("controlNumber", procurement.controlNumber().value())
        .param("objectDescription", procurement.objectDescription())
        .param("state", procurement.state().name())
        .param(
            "estimatedValue", procurement.estimatedValue().map(MonetaryValue::amount).orElse(null))
        .param("modalityCode", procurement.modality().code())
        .param("modalityName", procurement.modality().name())
        .param("publishedAt", utc(procurement.publishedAt()))
        .param("proposalOpensAt", utc(procurement.proposalOpensAt()))
        .param("proposalClosesAt", utc(procurement.proposalClosesAt()))
        .param("sourcePayloadHash", procurement.sourcePayloadHash())
        .param(
            "sourceUpdatedAt",
            procurement.sourceUpdatedAt().map(JdbcProcurementRepository::utc).orElse(null));
  }

  private static OffsetDateTime utc(Instant instant) {
    return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
  }

  private static Procurement toProcurement(ResultSet row, int rowNumber) throws SQLException {
    return new Procurement(
        new PncpControlNumber(row.getString("control_number")),
        row.getString("object_description"),
        BrazilianState.valueOf(row.getString("state")),
        Optional.ofNullable(row.getBigDecimal("estimated_value")).map(MonetaryValue::new),
        Modality.of(row.getInt("modality_code"), row.getString("modality_name")),
        instantAt(row, "published_at"),
        instantAt(row, "proposal_opens_at"),
        instantAt(row, "proposal_closes_at"),
        row.getString("source_payload_hash"),
        Optional.ofNullable(row.getObject("source_updated_at", OffsetDateTime.class))
            .map(OffsetDateTime::toInstant));
  }

  private static Instant instantAt(ResultSet row, String column) throws SQLException {
    return row.getObject(column, OffsetDateTime.class).toInstant();
  }
}
