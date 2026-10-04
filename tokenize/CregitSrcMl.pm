package CregitSrcMl;

# tokenizeSrcMl.pl in a persistent process, with one ctags per language. Its
# output must stay byte-identical to tokenizeSrcMl.pl (tests/test_token_worker.sh).

use strict;
use warnings;
use bytes;
use File::Temp ();
use POSIX qw(_exit);

our $PARSER_CRASH_EXIT = 33;

my $EMPTY_STREAM = "the token stream was empty. A successful srcML parse always emits at "
    . "least the begin_unit/end_unit wrapper, even for an empty file, so an "
    . "empty stream is a failed parse and never a legitimately empty result.";

sub new {
    my ($class, %options) = @_;
    my $self = bless { ctags => "ctags-universal", position => 0, %options,
                       ctags_procs => {}, retired_errors => [] }, $class;
    open(my $probe, '-|', $self->{srcml2token}, "--libsrcml-path")
        or die "unable to execute srcml2token [$self->{srcml2token}]: $!\n";
    my $library = <$probe> // "";
    close($probe);
    die "[$self->{srcml2token}] does not answer --libsrcml-path; rebuild it: make -C tokenize/srcMLtoken\n"
        if $? != 0 or $library eq "";
    return $self;
}

sub tokenize {
    my ($self, $language, $inputName, $workDir, $timeoutSecs) = @_;
    my $timedOut = 0;
    local $SIG{ALRM} = sub { $timedOut = 1; $self->kill_all() };
    alarm($timeoutSecs);
    my @result = eval { $self->run($language, $inputName, $workDir) };
    my $error = $@;
    alarm(0);
    my $helperErr = $self->take_helper_stderr();

    if ($timedOut) {
        $self->shutdown();
        return (124, "", $helperErr . "tokenize command timed out after [$timeoutSecs] seconds\n");
    }
    return (255, "", $helperErr . $error) if not @result;
    $result[2] = $helperErr . $result[2];
    return @result;
}

sub run {
    my ($self, $language, $inputName, $workDir) = @_;
    my $declarations = $self->read_declarations($language, $inputName, "$workDir/$inputName");
    my ($status, $tokens, $stderr) = $self->run_srcml2token($language, $inputName, $workDir);
    return parser_crash($inputName, $stderr, srcml2token_failure($status)) if $status != 0;
    return parser_crash($inputName, $stderr, $EMPTY_STREAM) if not @$tokens;
    return (0, merge($tokens, $declarations, $self->{position}), $stderr);
}

sub srcml2token_failure {
    my ($status) = @_;
    my $signal = $status & 127;
    return "srcml2token exited " . ($status >> 8) . "." if not $signal;
    return "srcml2token was killed by signal $signal. srcML 1.1.0, which it links, "
        . "dies on a signal on some C/C++ inputs under --position.";
}

sub parser_crash {
    my ($filename, $stderr, $why) = @_;
    return ($PARSER_CRASH_EXIT, "", $stderr
        . "cregit: tokenization of [$filename] FAILED: $why\n"
        . "cregit: refusing to emit a tokenization for [$filename]; "
        . "exiting $PARSER_CRASH_EXIT so the caller can count this blob.\n");
}

sub merge {
    my ($tokens, $declarations, $position) = @_;
    my $lastLine = -1;
    my $output = "";
    for my $tokenLine (@$tokens) {
        die "unable to parse srcml line [$tokenLine]\n" unless $tokenLine =~ /^([0-9]+|-):([0-9]+|-)\s+(.+)$/;
        my ($line, $col, $token) = ($1, $2, $3);
        my $lineNumber = $line eq '-' ? 0 : $line;
        $output .= declaration_lines($declarations->{$line}, $line, $position) if $lineNumber != $lastLine;
        $lastLine = $lineNumber;
        $output .= "$line:$col|" if $position;
        $output .= "$token\n";
        $output .= ($position ? "-:-|" : "") . "\n" if $token =~ /^end_/;
    }
    return $output;
}

# Like tokenizeSrcMl.pl, a name that is twice on one line prints the type of its
# last declaration twice.
sub declaration_lines {
    my ($declarations, $line, $position) = @_;
    return "" if not $declarations;
    return join "", map { ($position ? "$line:-|" : "") . "DECL|$declarations->{types}{$_}|$_\n" }
        @{ $declarations->{names} };
}

sub read_declarations {
    my ($self, $language, $inputName, $path) = @_;
    open(my $in, '<:raw', $path) or die "unable to read [$path]: $!\n";
    my $source = do { local $/; <$in> } // "";
    my $ctags = $self->ctags_for($language);
    print {$ctags->{in}} qq({"command":"generate-tags","filename":"$inputName","size":)
        . length($source) . "}\n", $source;
    $ctags->{in}->flush;

    my %declarations;
    while (defined(my $tag = readline($ctags->{out}))) {
        return \%declarations if $tag =~ /\A\{"_type": "completed"/;
        chomp $tag;
        if ($tag !~ /^([0-9]+) (.+) @@@ (.*) @@@ (.*)$/) {
            $self->retire(delete $self->{ctags_procs}{$language});
            die "unable to parse output [$tag]\n";
        }
        my ($line, $name, $type, $signature) = ($1, $2, $3, $4);
        $name .= " $signature" if $signature ne "-";
        $declarations{$line}{types}{$name} = $type;
        push @{ $declarations{$line}{names} }, $name;
    }
    $self->retire(delete $self->{ctags_procs}{$language});
    return \%declarations;
}

# The same -x format as tokenizeSrcMl.pl, so that the names are byte-identical:
# ctags --_interactive drops a JSON field that is not valid UTF-8.
sub ctags_for {
    my ($self, $language) = @_;
    return $self->{ctags_procs}{$language} //= do {
        my $proc = spawn_pipe($self->{ctags}, "--_interactive", "--language-force=$language", "-x",
                              "--sort=n", "--_xformat=%n %N @@@ %K @@@ %S");
        my $hello = readline($proc->{out}) // "EOF";
        die "ctags did not start: $hello\n" unless $hello =~ /"_type": "program"/;
        $proc;
    };
}

sub run_srcml2token {
    my ($self, $language, $inputName, $workDir) = @_;
    my $out = File::Temp->new(TEMPLATE => "cregit-srcml2token-out-XXXXX", TMPDIR => 1);
    my $err = File::Temp->new(TEMPLATE => "cregit-srcml2token-err-XXXXX", TMPDIR => 1);
    my $pid = fork() // die "unable to fork srcml2token: $!\n";
    if ($pid == 0) {
        setpgrp(0, 0);
        chdir($workDir) and open(STDIN, '<', '/dev/null') and open(STDOUT, '>', $out->filename)
            and open(STDERR, '>', $err->filename) or _exit(255);
        { no warnings 'exec'; exec $self->{srcml2token}, "-l", $language, $inputName; }
        print STDERR "unable to execute srcml2token [$self->{srcml2token}]: $!\n";
        _exit(255);
    }
    $self->{parser_pid} = $pid;
    waitpid($pid, 0);
    my $status = $?;
    delete $self->{parser_pid};
    seek($out, 0, 0);
    chomp(my @tokens = <$out>);
    return ($status, \@tokens, read_all($err));
}

# A helper writes its stderr to a file: a full stderr pipe would block it while
# this process waits for its stdout.
sub spawn_pipe {
    my (@command) = @_;
    pipe(my $childIn, my $parentOut) or die "unable to create pipe: $!\n";
    pipe(my $parentIn, my $childOut) or die "unable to create pipe: $!\n";
    my $err = File::Temp->new(TEMPLATE => "cregit-helper-err-XXXXX", TMPDIR => 1);
    my $pid = fork() // die "unable to fork [$command[0]]: $!\n";
    if ($pid == 0) {
        open(STDIN, '<&', $childIn) and open(STDOUT, '>&', $childOut)
            and open(STDERR, '>>', $err->filename) or _exit(255);
        { no warnings 'exec'; exec @command; }
        print STDERR "unable to execute [$command[0]]: $!\n";
        _exit(255);
    }
    close($childIn);
    close($childOut);
    binmode $parentOut;
    binmode $parentIn;
    return { pid => $pid, in => $parentOut, out => $parentIn, err => $err };
}

sub take_helper_stderr {
    my ($self) = @_;
    my @live = grep { $_ } values(%{ $self->{ctags_procs} });
    my $text = join "", map { read_all($_) } @{ $self->{retired_errors} }, map { $_->{err} } @live;
    $self->{retired_errors} = [];
    truncate($_->{err}, 0) for @live;
    return $text;
}

sub read_all {
    my ($fh) = @_;
    seek($fh, 0, 0);
    local $/;
    return <$fh> // "";
}

sub retire {
    my ($self, $proc) = @_;
    return if not $proc;
    push @{ $self->{retired_errors} }, $proc->{err};
    close($proc->{in});
    close($proc->{out});
    kill 'KILL', $proc->{pid};
    waitpid($proc->{pid}, 0);
}

sub kill_all {
    my ($self) = @_;
    if (my $pid = $self->{parser_pid}) {
        kill 'TERM', -$pid;
        select(undef, undef, undef, 0.1);
        kill 'KILL', -$pid;
    }
    kill 'KILL', map { $_->{pid} } grep { $_ } values(%{ $self->{ctags_procs} });
}

sub shutdown {
    my ($self) = @_;
    $self->retire(delete $self->{ctags_procs}{$_}) for keys %{ $self->{ctags_procs} };
}

1;
