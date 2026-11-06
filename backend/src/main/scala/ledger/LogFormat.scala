package ledger

/** `logback.xml` picks its console appender by the name in `LOG_FORMAT`. An unknown value (`JSON`,
  * `plain`, an empty string...) would leave Logback with no appender and silently drop every log line,
  * the audit trail included, so the app refuses to start instead.
  */
object LogFormat:

  val Supported: List[String] = List("text", "json")

  /** The configured value, from the same places Logback reads it: a JVM system property, then the environment. */
  def configured: Option[String] =
    sys.props.get("LOG_FORMAT").orElse(sys.env.get("LOG_FORMAT"))

  def validate(configured: Option[String]): Either[String, String] =
    configured match
      case None                                     => Right("text")
      case Some(value) if Supported.contains(value) => Right(value)
      case Some(value) =>
        Left(
          s"""Unsupported LOG_FORMAT "$value": use ${Supported.mkString(" or ")} (lowercase). """ +
            "Refusing to start, since Logback would silently drop every log line."
        )
